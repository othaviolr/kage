package com.kage.card.application.usecase;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.AnticipationPolicy;
import com.kage.card.domain.service.InstallmentPolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Antecipação de parcelas: as parcelas que estão em faturas futuras saem de lá e entram na fatura
 * atual (a aberta mais antiga) numa linha só. Vale para compra parcelada (sem juros: valor de face)
 * e para parcelamento de fatura (com juros: cada parcela a valor presente pela taxa do contrato, CDC
 * art. 52 §2º). O cliente escolhe quantas; saem sempre as últimas, que têm o maior desconto.
 *
 * O desconto reduz a dívida: devolve limite e tira os juros do teto legal. Fatura futura que fica
 * sem lançamento nenhum é removida.
 */
public class AnticipateInstallments {

    /** Diferença de arredondamento da parcela em centavos: abaixo disso, vale a taxa do contrato. */
    private static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.00001");

    public record FutureInstallment(int number, String referenceMonth, BigDecimal amount, BigDecimal presentValue) {}

    public record Plan(UUID planId, String kind, String description, int installmentCount, BigDecimal monthlyRate,
                       String currentInvoiceMonth, List<FutureInstallment> futureInstallments, BigDecimal futureTotal,
                       BigDecimal anticipateAllAmount) {}

    public record Output(UUID planId, String description, String status, int installments, int fromNumber,
                         int toNumber, String invoiceMonth, BigDecimal originalAmount, BigDecimal discount,
                         BigDecimal amount, BigDecimal monthlyRate, BigDecimal availableLimit) {}

    private record Scheduled(Invoice invoice, InvoiceItem item, int months) {}

    private record Context(Card card, Invoice current, UUID planId, InvoiceItemKind kind, String description,
                           int installmentCount, BigDecimal rate, List<Scheduled> future) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;

    public AnticipateInstallments(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
    }

    /** Planos (compras parceladas e parcelamentos de fatura) que ainda têm parcelas em faturas futuras. */
    public List<Plan> listPlans(UUID cardId) {
        Card card = findCard(cardId);
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        Invoice current = currentInvoice(invoices);
        if (current == null) return List.of();

        Map<UUID, Boolean> planIds = new LinkedHashMap<>();
        invoices.stream()
                .filter(invoice -> isFuture(invoice, current))
                .sorted(Comparator.comparing(Invoice::getReferenceMonth))
                .flatMap(invoice -> invoice.getItems().stream())
                .filter(item -> item.installmentNumber() != null && item.installmentCount() > 1)
                .forEach(item -> planIds.putIfAbsent(item.purchaseId(), true));

        return planIds.keySet().stream().map(planId -> toPlan(context(card, invoices, current, planId))).toList();
    }

    /** Simulação: não muda nada. */
    public Output quote(UUID cardId, UUID planId, int installments) {
        Card card = findCard(cardId);
        Context context = context(card, planId);
        return toOutput(context, chosen(context, installments), "SIMULATION");
    }

    @Transactional
    public Output execute(UUID cardId, UUID planId, int installments) {
        Card card = findCard(cardId);
        Context context = context(card, planId);
        List<Scheduled> chosen = chosen(context, installments);
        Output output = toOutput(context, chosen, "ANTICIPATED");

        for (Scheduled scheduled : chosen) {
            Invoice invoice = scheduled.invoice();
            invoice.removeInstallment(planId);
            if (invoice.isEmpty()) {
                invoiceRepository.delete(invoice);
            } else {
                invoiceRepository.save(invoice);
            }
        }

        String numbers = output.fromNumber() == output.toNumber()
                ? output.fromNumber() + "/" + context.installmentCount()
                : output.fromNumber() + " a " + output.toNumber() + "/" + context.installmentCount();
        context.current().addAnticipation(InvoiceItem.anticipation(planId, output.fromNumber(),
                "Antecipação: " + context.description() + " (" + numbers + ")", Money.of(output.amount()),
                DomainTime.now()));
        invoiceRepository.save(context.current());

        card.discountFinancedInterest(Money.of(output.discount()));
        Card saved = cardRepository.save(card);
        return new Output(output.planId(), output.description(), output.status(), output.installments(),
                output.fromNumber(), output.toNumber(), output.invoiceMonth(), output.originalAmount(), output.discount(),
                output.amount(), output.monthlyRate(), saved.availableLimit().amount());
    }

    private Context context(Card card, UUID planId) {
        List<Invoice> invoices = invoiceRepository.findByCardId(card.getId());
        Invoice current = currentInvoice(invoices);
        if (current == null) throw new BusinessRuleException("Não há fatura aberta para receber a antecipação");
        return context(card, invoices, current, planId);
    }

    private Context context(Card card, List<Invoice> invoices, Invoice current, UUID planId) {
        List<Scheduled> future = new ArrayList<>();
        for (Invoice invoice : invoices) {
            if (!isFuture(invoice, current)) continue;
            invoice.getItems().stream()
                    .filter(item -> item.purchaseId().equals(planId) && item.installmentNumber() != null)
                    .findFirst()
                    .ifPresent(item -> future.add(new Scheduled(invoice, item,
                            (int) current.getReferenceMonth().until(invoice.getReferenceMonth(), ChronoUnit.MONTHS))));
        }
        if (future.isEmpty()) throw new NotFoundException("Nenhuma parcela futura deste plano para antecipar");
        future.sort(Comparator.comparing(s -> s.item().installmentNumber()));

        InvoiceItem sample = future.get(future.size() - 1).item(); // da 2ª em diante, todas têm o valor base
        BigDecimal rate = sample.kind() == InvoiceItemKind.INVOICE_INSTALLMENT
                ? contractRate(invoices, planId, sample)
                : BigDecimal.ZERO;
        String description = sample.description().replaceFirst("\\s*\\(\\d+/\\d+\\)$", "");
        return new Context(card, current, planId, sample.kind(), description, sample.installmentCount(), rate, future);
    }

    /**
     * Taxa do parcelamento da fatura: a implícita no plano (principal = saldo da fatura parcelada).
     * Se o teto legal cortou os juros, ela é menor que a da tabela — e o desconto acompanha.
     */
    private static BigDecimal contractRate(List<Invoice> invoices, UUID planId, InvoiceItem sample) {
        Invoice source = invoices.stream()
                .filter(invoice -> invoice.getStatus() == InvoiceStatus.FINANCED)
                .filter(invoice -> InvoiceItem.invoiceInstallmentPlanId(invoice.getId()).equals(planId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Fatura de origem do parcelamento " + planId + " não encontrada"));
        BigDecimal implied = AnticipationPolicy.impliedMonthlyRate(source.remaining(), sample.amount(), sample.installmentCount());
        BigDecimal table = InstallmentPolicy.INVOICE_MONTHLY_RATE;
        return implied.compareTo(table.subtract(RATE_TOLERANCE)) >= 0 ? table : implied;
    }

    private static List<Scheduled> chosen(Context context, int installments) {
        int available = context.future().size();
        if (installments < 1 || installments > available) {
            throw new ValidationException("Dá para antecipar de 1 a " + available + " parcela(s) deste plano");
        }
        return context.future().subList(available - installments, available); // as últimas: maior desconto
    }

    private static Output toOutput(Context context, List<Scheduled> chosen, String status) {
        Money original = Money.ZERO;
        Money amount = Money.ZERO;
        for (Scheduled scheduled : chosen) {
            original = original.add(scheduled.item().amount());
            amount = amount.add(AnticipationPolicy.presentValue(scheduled.item().amount(), context.rate(), scheduled.months()));
        }
        return new Output(context.planId(), context.description(), status, chosen.size(),
                chosen.get(0).item().installmentNumber(), chosen.get(chosen.size() - 1).item().installmentNumber(),
                context.current().getReferenceMonth().toString(), original.amount(), original.subtract(amount).amount(),
                amount.amount(), context.rate(), context.card().availableLimit().amount());
    }

    private static Plan toPlan(Context context) {
        List<FutureInstallment> future = context.future().stream()
                .map(s -> new FutureInstallment(s.item().installmentNumber(), s.invoice().getReferenceMonth().toString(),
                        s.item().amount().amount(),
                        AnticipationPolicy.presentValue(s.item().amount(), context.rate(), s.months()).amount()))
                .toList();
        BigDecimal total = future.stream().map(FutureInstallment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal all = future.stream().map(FutureInstallment::presentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Plan(context.planId(), context.kind().name(), context.description(), context.installmentCount(),
                context.rate(), context.current().getReferenceMonth().toString(), future, total, all);
    }

    /** A fatura que recebe a antecipação: a aberta mais antiga (a do ciclo atual). */
    private static Invoice currentInvoice(List<Invoice> invoices) {
        return invoices.stream()
                .filter(invoice -> invoice.getStatus() == InvoiceStatus.OPEN)
                .min(Comparator.comparing(Invoice::getReferenceMonth))
                .orElse(null);
    }

    private static boolean isFuture(Invoice invoice, Invoice current) {
        return invoice.getStatus() == InvoiceStatus.OPEN && invoice.getReferenceMonth().isAfter(current.getReferenceMonth());
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
