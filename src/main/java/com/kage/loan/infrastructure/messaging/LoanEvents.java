package com.kage.loan.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Eventos publicados no loan.exchange. */
public final class LoanEvents {

    private LoanEvents() {
    }

    public static final String EXCHANGE = "loan.exchange";
    public static final String CONTRACTED = "loan.contracted";
    public static final String INSTALLMENT_PAID = "loan.installment.paid";
    public static final String INSTALLMENT_FAILED = "loan.installment.failed";
    public static final String PAID_OFF = "loan.paid-off";
    public static final String WITHDRAWN = "loan.withdrawn";
    public static final String RENEGOTIATED = "loan.renegotiated";
    public static final String INSTALLMENT_DUE_SOON = "loan.installment.due-soon";

    public record ContractedEvent(UUID loanId, UUID accountId, BigDecimal released, int installments,
                                  BigDecimal installmentAmount, LocalDate firstDueDate, Instant occurredAt) {}

    /** @param remaining parcelas que ainda faltam (0 = quitou) */
    public record InstallmentPaidEvent(UUID loanId, UUID accountId, int number, int installments, BigDecimal paid,
                                       BigDecimal lateCharges, BigDecimal discount, int remaining, Instant occurredAt) {}

    public record InstallmentFailedEvent(UUID loanId, UUID accountId, int number, int installments, BigDecimal due,
                                         LocalDate dueDate, String reason, Instant occurredAt) {}

    public record PaidOffEvent(UUID loanId, UUID accountId, BigDecimal paid, BigDecimal discount, Instant occurredAt) {}

    /** {@code loanId} é o acordo; {@code debt}, o que se devia antes da entrada. */
    public record RenegotiatedEvent(UUID loanId, UUID originalLoanId, UUID accountId, java.math.BigDecimal debt,
                                    java.math.BigDecimal downPayment, int installments, java.math.BigDecimal installmentAmount,
                                    java.time.LocalDate firstDueDate, Instant occurredAt) {}

    public record WithdrawnEvent(UUID loanId, UUID accountId, BigDecimal returned, Instant occurredAt) {}

    public record InstallmentDueSoonEvent(UUID loanId, UUID accountId, int number, int installments, BigDecimal amount,
                                          LocalDate dueDate, Instant occurredAt) {}
}
