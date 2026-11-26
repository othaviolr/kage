package com.kage.loan.infrastructure.messaging;

import com.kage.loan.application.usecase.LoanEventPublisher;
import com.kage.loan.domain.entity.Loan;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.valueobject.Money;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class OutboxLoanEventPublisher implements LoanEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxLoanEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishContracted(Loan loan) {
        Loan.Installment first = loan.getInstallments().get(0);
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.CONTRACTED, new LoanEvents.ContractedEvent(loan.getId(),
                loan.getAccountId(), loan.getReleased().amount(), loan.getInstallments().size(), first.amount().amount(),
                first.dueDate(), DomainTime.instant()));
    }

    @Override
    public void publishRenegotiated(Loan original, Loan agreement, Money downPayment) {
        Loan.Installment first = agreement.getInstallments().get(0);
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.RENEGOTIATED, new LoanEvents.RenegotiatedEvent(agreement.getId(),
                original.getId(), agreement.getAccountId(), agreement.getReleased().add(downPayment).amount(),
                downPayment.amount(), agreement.getInstallments().size(), first.amount().amount(), first.dueDate(),
                DomainTime.instant()));
    }

    @Override
    public void publishInstallmentPaid(Loan loan, Loan.Installment i) {
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.INSTALLMENT_PAID, new LoanEvents.InstallmentPaidEvent(loan.getId(),
                loan.getAccountId(), i.number(), loan.getInstallments().size(), i.paidAmount().amount(),
                i.lateCharges().amount(), i.discount().amount(), loan.pending().size(), DomainTime.instant()));
    }

    @Override
    @Transactional
    public void publishInstallmentFailed(Loan loan, Loan.Installment i, Money due, String reason) {
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.INSTALLMENT_FAILED, new LoanEvents.InstallmentFailedEvent(loan.getId(),
                loan.getAccountId(), i.number(), loan.getInstallments().size(), due.amount(), i.dueDate(), reason,
                DomainTime.instant()));
    }

    @Override
    public void publishPaidOff(Loan loan, Money paid, Money discount) {
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.PAID_OFF, new LoanEvents.PaidOffEvent(loan.getId(), loan.getAccountId(),
                paid.amount(), discount.amount(), DomainTime.instant()));
    }

    @Override
    public void publishWithdrawn(Loan loan, Money returned) {
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.WITHDRAWN, new LoanEvents.WithdrawnEvent(loan.getId(),
                loan.getAccountId(), returned.amount(), DomainTime.instant()));
    }

    @Override
    public void publishInstallmentDueSoon(Loan loan, Loan.Installment i) {
        outbox.publish(LoanEvents.EXCHANGE, LoanEvents.INSTALLMENT_DUE_SOON, new LoanEvents.InstallmentDueSoonEvent(
                loan.getId(), loan.getAccountId(), i.number(), loan.getInstallments().size(), i.amount().amount(),
                i.dueDate(), DomainTime.instant()));
    }
}
