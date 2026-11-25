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
