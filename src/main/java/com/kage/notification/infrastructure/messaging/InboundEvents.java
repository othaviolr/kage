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
    public record InvoiceFinanced(UUID cardId, UUID invoiceId, String referenceMonth, int installments,
                                  BigDecimal installmentAmount, BigDecimal total, boolean automatic) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisputeOpened(UUID cardId, UUID disputeId, String description, BigDecimal amount,
                                BigDecimal creditedAmount, String creditMonth, boolean cardBlocked,
                                boolean virtualCardDeleted) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseApproved(UUID cardId, UUID purchaseId, String description, BigDecimal amount, int installments,
                                   BigDecimal installmentAmount, BigDecimal availableLimit, String virtualCardNickname) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PurchaseDeclined(UUID cardId, String description, BigDecimal amount, String reason,
                                   String virtualCardNickname) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanContracted(UUID loanId, UUID accountId, BigDecimal released, int installments,
                                 BigDecimal installmentAmount, LocalDate firstDueDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanInstallmentPaid(UUID loanId, UUID accountId, int number, int installments, BigDecimal paid,
                                      BigDecimal lateCharges, BigDecimal discount, int remaining) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanInstallmentFailed(UUID loanId, UUID accountId, int number, int installments, BigDecimal due,
                                        LocalDate dueDate, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanPaidOff(UUID loanId, UUID accountId, BigDecimal paid, BigDecimal discount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanWithdrawn(UUID loanId, UUID accountId, BigDecimal returned) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoanInstallmentDueSoon(UUID loanId, UUID accountId, int number, int installments, BigDecimal amount,
                                         LocalDate dueDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CashbackEarned(UUID cardId, String referenceMonth, BigDecimal amount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LimitOffered(UUID cardId, UUID offerId, BigDecimal offeredLimit, java.time.LocalDateTime expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardReissued(UUID cardId, UUID plasticId, String lastDigits, boolean oldNumberCancelled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisputeResolved(UUID cardId, UUID disputeId, String description, BigDecimal amount, String status,
                                  String resolutionNote, String reversalMonth, BigDecimal interestRefunded) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduledPixFailed(UUID transactionId, UUID sourceAccountId, BigDecimal amount, LocalDate scheduledDate,
                                     String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PixAutomaticRequested(UUID authorizationId, UUID payerAccountId, String receiverName, String description,
                                        String frequency, BigDecimal fixedAmount, LocalDate startDate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PixAutomaticChargeScheduled(UUID chargeId, UUID authorizationId, UUID payerAccountId,
                                              String receiverName, String description, BigDecimal amount,
                                              LocalDate dueDate, Integer attempt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PasswordChanged(UUID customerId, String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LimitIncreaseRequested(UUID requestId, UUID accountId, String limitType, String limitLabel,
                                         BigDecimal requestedAmount, java.time.LocalDateTime effectiveAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceTrusted(UUID customerId, String userAgent, Instant occurredAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoletoPaid(UUID boletoId, UUID beneficiaryAccountId, String payerName, BigDecimal paidAmount, String via) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BoletoPaymentMade(UUID paymentId, UUID payerAccountId, String beneficiaryName, BigDecimal paidAmount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavingsGoalReached(UUID boxId, UUID customerId, String name, BigDecimal goalAmount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceAutoDebitFailed(UUID cardId, String referenceMonth, String reason, Instant occurredAt) {}
}
