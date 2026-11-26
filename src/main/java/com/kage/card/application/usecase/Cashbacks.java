package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CashbackPolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cashback do cartão: creditado na fatura seguinte quando a fatura é paga inteira até o vencimento
 * (linha de crédito: abate o total e devolve limite no fechamento), e estornado se uma compra que
 * rendeu cashback for contestada com sucesso depois.
 *
 * Idempotente: o crédito de uma fatura e o estorno de uma contestação têm id determinístico.
 */
public class Cashbacks {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record InvoiceCashback(String referenceMonth, BigDecimal amount, String creditedIn) {}

    public record Summary(UUID cardId, BigDecimal rate, BigDecimal earned, BigDecimal reversed, BigDecimal net,
                          List<InvoiceCashback> invoices) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final DisputeRepository disputeRepository;
    private final CardEventPublisher eventPublisher;

    public Cashbacks(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                     DisputeRepository disputeRepository, CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.disputeRepository = disputeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Fatura acabou de ser quitada: se foi em dia, credita o cashback na fatura seguinte. Chamar dentro
     * da transação do pagamento (a fatura paga ainda em memória).
     *
     * @return o valor creditado (zero se não teve direito)
     */
    public Money creditFor(Card card, Invoice paid) {
        if (!CashbackPolicy.eligible(paid)) return Money.ZERO;
        Set<UUID> disputed = disputeRepository.findByCardId(card.getId()).stream()
                .map(Dispute::purchaseId).collect(Collectors.toSet());
        Money cashback = CashbackPolicy.of(CashbackPolicy.base(paid, disputed));
        if (cashback.isZero()) return Money.ZERO;

        Invoice target = openInvoiceFrom(card, paid.getReferenceMonth().plusMonths(1));
        target.addCredit(InvoiceItemKind.CASHBACK, paid.getId(),
                "Cashback de 1% da fatura de " + paid.getReferenceMonth().format(MONTH), cashback, DomainTime.now());
        invoiceRepository.save(target);
        eventPublisher.publishCashbackEarned(card.getId(), paid.getReferenceMonth().toString(), cashback);
        return cashback;
    }

    /**
     * Contestação procedente de uma compra que já rendeu cashback: a parte dela volta a ser cobrada na
     * fatura atual. Chamar dentro da transação da resolução.
     *
     * @return o valor estornado
     */
    public Money reverseFor(Card card, Dispute dispute, List<Invoice> invoices, Invoice current) {
        Money reversal = Money.ZERO;
        for (Invoice invoice : invoices) {
            // só se o cashback daquela fatura saiu ANTES da contestação: se ela já existia, a compra ficou de fora
            Optional<InvoiceItem> credited = cashbackLineFor(invoices, invoice);
            if (credited.isEmpty() || !credited.get().purchasedAt().isBefore(dispute.openedAt())) continue;
            Money lines = invoice.getItems().stream()
                    .filter(i -> i.isPurchase() && i.purchaseId().equals(dispute.purchaseId()))
                    .map(InvoiceItem::amount).reduce(Money.ZERO, Money::add);
            reversal = reversal.add(CashbackPolicy.of(lines));
        }
        if (reversal.isZero()) return Money.ZERO;
        current.addCharge(InvoiceItemKind.CASHBACK_REVERSAL, dispute.id(),
                "Estorno de cashback: " + dispute.description(), reversal, DomainTime.now());
        card.reinstateCharge(reversal);
        return reversal;
    }

    public Summary summary(UUID cardId) {
        cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        List<InvoiceCashback> details = new ArrayList<>();
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal reversed = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            for (InvoiceItem item : invoice.getItems()) {
                if (item.kind() == InvoiceItemKind.CASHBACK) {
                    earned = earned.add(item.amount().amount());
                    details.add(new InvoiceCashback(sourceMonth(invoices, item), item.amount().amount(),
                            invoice.getReferenceMonth().toString()));
                } else if (item.kind() == InvoiceItemKind.CASHBACK_REVERSAL) {
                    reversed = reversed.add(item.amount().amount());
                }
            }
        }
        details.sort(Comparator.comparing(InvoiceCashback::referenceMonth).reversed());
        return new Summary(cardId, CashbackPolicy.RATE, earned, reversed, earned.subtract(reversed), details);
    }

    /** A linha de cashback que a fatura {@code invoice} gerou (na fatura seguinte), se houve. */
    private static Optional<InvoiceItem> cashbackLineFor(List<Invoice> invoices, Invoice invoice) {
        UUID lineId = InvoiceItem.chargeId(InvoiceItemKind.CASHBACK, invoice.getId());
        return invoices.stream().flatMap(i -> i.getItems().stream()).filter(item -> item.purchaseId().equals(lineId)).findFirst();
    }

    /** Mês da fatura que gerou este cashback (o id da linha deriva dela). */
    private static String sourceMonth(List<Invoice> invoices, InvoiceItem cashbackLine) {
        return invoices.stream()
                .filter(i -> InvoiceItem.chargeId(InvoiceItemKind.CASHBACK, i.getId()).equals(cashbackLine.purchaseId()))
                .map(i -> i.getReferenceMonth().toString())
                .findFirst().orElse(null);
    }

    private Invoice openInvoiceFrom(Card card, YearMonth from) {
        YearMonth month = from;
        while (true) {
            Optional<Invoice> existing = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month);
            if (existing.isEmpty()) return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
            if (existing.get().getStatus() == InvoiceStatus.OPEN) return existing.get();
            month = month.plusMonths(1);
        }
    }
}
