package com.kage.card.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Eventos publicados no card.exchange. */
public final class CardEvents {

    private CardEvents() {
    }

    public static final String EXCHANGE = "card.exchange";
    public static final String INVOICE_CLOSED = "card.invoice.closed";
    public static final String INVOICE_PAID = "card.invoice.paid";
    public static final String AUTO_DEBIT_FAILED = "card.invoice.auto-debit-failed";
    public static final String INVOICE_PARTIALLY_PAID = "card.invoice.partially-paid";
    public static final String INVOICE_ROLLED_OVER = "card.invoice.rolled-over";
    public static final String INVOICE_FINANCED = "card.invoice.financed";
    public static final String DISPUTE_OPENED = "card.dispute.opened";
    public static final String DISPUTE_RESOLVED = "card.dispute.resolved";
    public static final String CARD_REISSUED = "card.reissued";
    public static final String PURCHASE_APPROVED = "card.purchase.approved";
    public static final String PURCHASE_DECLINED = "card.purchase.declined";
    public static final String LIMIT_OFFERED = "card.limit.offered";

    public record LimitOfferedEvent(UUID cardId, UUID offerId, BigDecimal offeredLimit, java.time.LocalDateTime expiresAt,
                                    Instant occurredAt) {}

    /** @param virtualCardNickname compra com cartão virtual: o nome dele; nulo = físico */
    public record PurchaseApprovedEvent(UUID cardId, UUID purchaseId, String description, BigDecimal amount,
                                        int installments, BigDecimal installmentAmount, BigDecimal availableLimit,
                                        String virtualCardNickname, Instant occurredAt) {}

    public record PurchaseDeclinedEvent(UUID cardId, String description, BigDecimal amount, String reason,
                                        String virtualCardNickname, Instant occurredAt) {}

    /** @param oldNumberCancelled perda, roubo ou fraude: o plástico antigo já não funciona */
    public record CardReissuedEvent(UUID cardId, UUID plasticId, String lastDigits, String reason,
                                    boolean oldNumberCancelled, Instant occurredAt) {}

    /** @param creditMonth fatura que recebeu o crédito do que já tinha sido cobrado; nulo se nada tinha */
    public record DisputeOpenedEvent(UUID cardId, UUID disputeId, String description, BigDecimal amount,
                                     BigDecimal creditedAmount, String creditMonth, String reason, boolean cardBlocked,
                                     boolean virtualCardDeleted, Instant occurredAt) {}

    /** @param reversalMonth negada: fatura em que a compra volta a ser cobrada */
    public record DisputeResolvedEvent(UUID cardId, UUID disputeId, String description, BigDecimal amount,
                                       String status, String resolutionNote, String reversalMonth,
                                       BigDecimal interestRefunded, Instant occurredAt) {}

    /** @param automatic parcelado pela regra do Bacen (rotativo não pago por dois ciclos), não pelo cliente */
    public record InvoiceFinancedEvent(UUID cardId, UUID invoiceId, String referenceMonth, int installments,
                                       BigDecimal installmentAmount, BigDecimal total, boolean automatic,
                                       Instant occurredAt) {}

    public record InvoicePartiallyPaidEvent(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal amountPaid,
                                            BigDecimal remaining, BigDecimal minimumPayment, BigDecimal totalPaid,
                                            Instant occurredAt) {}

    /** @param late o cliente pagou menos que o mínimo: os encargos incluem multa e mora */
    public record InvoiceRolledOverEvent(UUID cardId, String fromReferenceMonth, String toReferenceMonth,
                                         BigDecimal carried, BigDecimal charges, boolean late, Instant occurredAt) {}

    public record InvoiceClosedEvent(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal total,
                                     LocalDate dueDate, Instant occurredAt) {}

    public record InvoicePaidEvent(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal amount,
                                   Instant occurredAt) {}

    public record InvoiceAutoDebitFailedEvent(UUID cardId, String referenceMonth, String reason, Instant occurredAt) {}
}
