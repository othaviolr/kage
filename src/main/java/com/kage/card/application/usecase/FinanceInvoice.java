package com.kage.card.application.usecase;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.InstallmentPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
 *
 * Também usado pelo fechamento para o parcelamento automático da regra do Bacen.
 */
public class FinanceInvoice {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record InstallmentView(int number, String referenceMonth, BigDecimal amount) {}
    public record Output(UUID invoiceId, String referenceMonth, String status, int installments, BigDecimal principal,
                         BigDecimal interest, BigDecimal total, BigDecimal monthlyRate, List<InstallmentView> schedule) {}

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
        return toOutput(invoice, "SIMULATION", quote, invoice.getReferenceMonth().plusMonths(1));
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
        for (int number = 1; number <= installments; number++) {
            Invoice target = (number == 1 && firstInvoiceInMemory != null)
                    ? firstInvoiceInMemory
                    : openInvoiceFrom(card, month);
            target.addInvoiceInstallment(invoice.getId(), "Parcelamento da fatura de " + source + " (" + number + "/" + installments + ")",
                    quote.amounts().get(number - 1), DomainTime.now(), number, installments);
            if (target != firstInvoiceInMemory) invoiceRepository.save(target);
            month = target.getReferenceMonth().plusMonths(1);
        }

        eventPublisher.publishInvoiceFinanced(invoice, quote.installments(), quote.amounts().get(quote.installments() - 1),
                quote.total(), firstInvoiceInMemory != null);
        return toOutput(invoice, invoice.getStatus().name(), quote, firstMonth);
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

    private static Output toOutput(Invoice invoice, String status, InstallmentPolicy.Quote quote, YearMonth firstMonth) {
        List<InstallmentView> schedule = new ArrayList<>();
        for (int k = 0; k < quote.installments(); k++) {
            schedule.add(new InstallmentView(k + 1, firstMonth.plusMonths(k).toString(), quote.amounts().get(k).amount()));
        }
        return new Output(invoice.getId(), invoice.getReferenceMonth().toString(), status, quote.installments(),
                quote.principal().amount(), quote.interest().amount(), quote.total().amount(), quote.monthlyRate(), schedule);
    }
}
