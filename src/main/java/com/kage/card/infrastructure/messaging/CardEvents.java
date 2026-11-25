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

    public record InvoiceClosedEvent(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal total,
                                     LocalDate dueDate, Instant occurredAt) {}

    public record InvoicePaidEvent(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal amount,
                                   Instant occurredAt) {}

    public record InvoiceAutoDebitFailedEvent(UUID cardId, String referenceMonth, String reason, Instant occurredAt) {}
}
