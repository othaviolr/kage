package com.kage.card.application.usecase;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.service.CetPolicy;
import com.kage.card.domain.service.InstallmentPolicy;
import com.kage.card.domain.service.IofPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parcelamento da fatura (com juros, Tabela Price): simular e contratar.
 *
 * Contratar tira o saldo devedor da fatura (FINANCED: não vai para o rotativo nem aceita mais
 * pagamento) e lança uma parcela em cada uma das N faturas seguintes. Os juros:
 * - contam no teto da Lei 14.690/2023 junto com os do rotativo (o Card corta o excedente);
 * - aumentam a dívida, então consomem limite.
 * O IOF (0,38% + 0,0082% ao dia sobre o que cada parcela amortiza) vai numa linha própria na fatura da
 * 1ª parcela — fora do teto, é imposto. A simulação mostra o CET (Res. CMN 3.517): a taxa que inclui
 * juros e IOF, contada do vencimento da fatura parcelada.
 *
 * Também usado pelo fechamento para o parcelamento automático da regra do Bacen.
 */
public class FinanceInvoice {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record InstallmentView(int number, String referenceMonth, BigDecimal amount) {}
    /**
     * @param iof        imposto, cobrado na fatura da 1ª parcela
     * @param cetMonthly CET ao mês e @param cetYearly ao ano (frações: 0,1234 = 12,34%)
     */
    public record Output(UUID invoiceId, String referenceMonth, String status, int installments, BigDecimal principal,
                         BigDecimal interest, BigDecimal total, BigDecimal monthlyRate, List<InstallmentView> schedule,
                         BigDecimal iof, BigDecimal cetMonthly, BigDecimal cetYearly) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CardEventPublisher eventPublisher;

    public FinanceInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                          CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Simulação: não muda nada. Já mostra o efeito do teto legal, se ele cortar os juros. */
    public Output quote(UUID cardId, String referenceMonth, int installments) {
        Card card = findCard(cardId);
        Invoice invoice = findFinanceable(card, ReferenceMonths.parse(referenceMonth));
        InstallmentPolicy.Quote quote = cappedQuote(card, invoice.remaining(), installments);
        YearMonth firstMonth = invoice.getReferenceMonth().plusMonths(1);
        List<LocalDate> dueDates = new ArrayList<>();
        for (int k = 0; k < quote.installments(); k++) {
            dueDates.add(Invoice.dueDateOf(firstMonth.plusMonths(k), card.getClosingDay(), card.getDueDay()));
        }
        return toOutput(invoice, "SIMULATION", quote, firstMonth, dueDates);
    }

    @Transactional
    public Output execute(UUID cardId, String referenceMonth, int installments) {
        Card card = findCard(cardId);
        Invoice invoice = findFinanceable(card, ReferenceMonths.parse(referenceMonth));

        Output output = finance(card, invoice, installments, invoice.getReferenceMonth().plusMonths(1), null);
        invoiceRepository.save(invoice);
        cardRepository.save(card);
        return output;
    }

    /**
     * Parcela {@code invoice} lançando as parcelas a partir de {@code firstMonth}. Se a fatura do
     * primeiro mês já estiver carregada pelo chamador (o fechamento em andamento), ela vem em
     * {@code firstInvoiceInMemory} para a parcela 1 ir no mesmo objeto que ele vai salvar. Não salva
     * {@code invoice} nem o card: o chamador salva na transação dele.
     *
     * Público de propósito: este bean é um proxy CGLIB (por causa do @Transactional), e método
     * package-private chamado de outro bean pode não ser delegado ao objeto real — rodaria no proxy,
     * com os campos nulos.
     */
    public Output finance(Card card, Invoice invoice, int installments, YearMonth firstMonth, Invoice firstInvoiceInMemory) {
        Money principal = invoice.remaining();
        InstallmentPolicy.Quote quote = cappedQuote(card, principal, installments);

        invoice.finance(DomainTime.now());
        Money interestApplied = card.applyRevolvingCharges(principal, quote.interest());
        if (interestApplied.isLessThan(quote.interest())) {
            quote = InstallmentPolicy.capInterest(quote, interestApplied); // corrida com outra virada: respeita o teto
        }

        String source = invoice.getReferenceMonth().format(MONTH);
        YearMonth month = firstMonth;
        List<Invoice> targets = new ArrayList<>();
        for (int number = 1; number <= installments; number++) {
            Invoice target = (number == 1 && firstInvoiceInMemory != null)
                    ? firstInvoiceInMemory
                    : openInvoiceFrom(card, month);
            target.addInvoiceInstallment(invoice.getId(), "Parcelamento da fatura de " + source + " (" + number + "/" + installments + ")",
                    quote.amounts().get(number - 1), DomainTime.now(), number, installments);
            targets.add(target);
            month = target.getReferenceMonth().plusMonths(1);
        }

        // IOF na fatura da 1ª parcela, pelos dias de cada amortização desde o vencimento da fatura parcelada
        List<LocalDate> dueDates = targets.stream().map(Invoice::getDueDate).toList();
        Money iof = IofPolicy.installments(quote.principal(), InstallmentPolicy.amortizations(quote),
                CetPolicy.days(invoice.getDueDate(), dueDates));
        targets.get(0).addCharge(InvoiceItemKind.IOF, invoice.getId(), "IOF do parcelamento da fatura de " + source,
                iof, DomainTime.now());
        card.addTax(iof);
        for (Invoice target : targets) {
            if (target != firstInvoiceInMemory) invoiceRepository.save(target);
        }

        eventPublisher.publishInvoiceFinanced(invoice, quote.installments(), quote.amounts().get(quote.installments() - 1),
                quote.total(), firstInvoiceInMemory != null);
        return toOutput(invoice, invoice.getStatus().name(), quote, firstMonth, dueDates);
    }

    private InstallmentPolicy.Quote cappedQuote(Card card, Money principal, int installments) {
        InstallmentPolicy.Quote quote = InstallmentPolicy.quoteInvoice(principal, installments);
        Money reference = card.hasRevolvingDebt() ? card.getRevolvingPrincipal() : principal;
        Money room = card.getRevolvingCharges().isGreaterThan(reference) ? Money.ZERO : reference.subtract(card.getRevolvingCharges());
        return InstallmentPolicy.capInterest(quote, room);
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }

    private Invoice findFinanceable(Card card, YearMonth month) {
        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + month));
        if (invoice.getStatus() == InvoiceStatus.FINANCED) throw new BusinessRuleException("Fatura já foi parcelada");
        if (invoice.getStatus() != InvoiceStatus.CLOSED || invoice.remaining().isZero()) {
            throw new BusinessRuleException("Somente faturas fechadas com saldo podem ser parceladas");
        }
        return invoice;
    }

    private Invoice openInvoiceFrom(Card card, YearMonth from) {
        YearMonth month = from;
        while (true) {
            var existing = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month);
            if (existing.isEmpty()) return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
            if (existing.get().getStatus() == InvoiceStatus.OPEN) return existing.get();
            month = month.plusMonths(1);
        }
    }

    private static Output toOutput(Invoice invoice, String status, InstallmentPolicy.Quote quote, YearMonth firstMonth,
                                   List<LocalDate> dueDates) {
        List<InstallmentView> schedule = new ArrayList<>();
        for (int k = 0; k < quote.installments(); k++) {
            schedule.add(new InstallmentView(k + 1, firstMonth.plusMonths(k).toString(), quote.amounts().get(k).amount()));
        }
        Money iof = IofPolicy.installments(quote.principal(), InstallmentPolicy.amortizations(quote),
                CetPolicy.days(invoice.getDueDate(), dueDates));
        CetPolicy.Cet cet = CetPolicy.installment(quote, invoice.getDueDate(), dueDates);
        return new Output(invoice.getId(), invoice.getReferenceMonth().toString(), status, quote.installments(),
                quote.principal().amount(), quote.interest().amount(), quote.total().amount(), quote.monthlyRate(), schedule,
                iof.amount(), cet.monthly(), cet.yearly());
    }
}
