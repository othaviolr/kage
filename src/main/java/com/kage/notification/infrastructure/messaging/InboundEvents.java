package com.kage.notification.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cópia local dos eventos que a notificação consome (cada contexto tem a sua, como no resto do
 * projeto: o JSON é o contrato, não a classe). Só os campos que o aviso usa; o resto é ignorado, então o
 * produtor pode acrescentar campos sem quebrar este consumidor.
 */
public final class InboundEvents {

    private InboundEvents() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountMovement(UUID accountId, BigDecimal amount, Instant occurredAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountPixMovement(UUID accountId, UUID referenceId, BigDecimal amount, Instant occurredAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountPixFailed(UUID accountId, UUID referenceId, BigDecimal amount, String reason, Instant occurredAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceClosed(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal total, LocalDate dueDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoicePaid(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoicePartiallyPaid(UUID cardId, UUID invoiceId, String referenceMonth, BigDecimal amountPaid,
                                       BigDecimal remaining, BigDecimal minimumPayment, BigDecimal totalPaid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceRolledOver(UUID cardId, String fromReferenceMonth, String toReferenceMonth, BigDecimal carried,
                                    BigDecimal charges, boolean late) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduledPixFailed(UUID transactionId, UUID sourceAccountId, BigDecimal amount, LocalDate scheduledDate,
                                     String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PasswordChanged(UUID customerId, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceAutoDebitFailed(UUID cardId, String referenceMonth, String reason, Instant occurredAt) {}
}
