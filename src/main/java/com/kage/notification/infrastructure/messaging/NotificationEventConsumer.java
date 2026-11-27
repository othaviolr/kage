package com.kage.notification.infrastructure.messaging;

import com.kage.notification.application.usecase.NotificationTemplates;
import com.kage.notification.application.usecase.Notify;
import com.kage.notification.application.usecase.SendInvoiceEmail;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.service.RecipientDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import java.util.Optional;
import java.util.UUID;

/**
 * Traduz eventos de negócio em avisos ao cliente. Só lê: nunca altera conta, PIX ou fatura.
 *
 * A idempotência vem do messageId, que o OutboxRelay põe em toda mensagem (é o id da linha do
 * outbox): a reentrega traz o mesmo id e o Notify ignora. Sem messageId (mensagem publicada fora do
 * outbox), cada entrega vira um aviso — não há como saber que é a mesma.
 */
public class NotificationEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final Notify notify;
    private final RecipientDirectory recipients;
    private final SendInvoiceEmail sendInvoiceEmail;

    public NotificationEventConsumer(Notify notify, RecipientDirectory recipients) {
        this(notify, recipients, null);
    }

    public NotificationEventConsumer(Notify notify, RecipientDirectory recipients, SendInvoiceEmail sendInvoiceEmail) {
        this.notify = notify;
        this.recipients = recipients;
        this.sendInvoiceEmail = sendInvoiceEmail;
    }

    /**
     * Fila própria para o e-mail da fatura (mesmo evento do push): gerar o PDF ou enviar pode falhar e
     * ser reentregue sem duplicar o push — e o push não espera o PDF.
     */
    @RabbitListener(queues = NotificationRabbitMQConfig.INVOICE_EMAIL_QUEUE)
    public void onInvoiceClosedEmail(InboundEvents.InvoiceClosed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        sendInvoiceEmail.execute(new SendInvoiceEmail.Input(e.cardId(), e.invoiceId(), e.referenceMonth(), e.total(),
                e.dueDate(), messageId));
    }

    @RabbitListener(queues = "notification.account.deposit.made.queue")
    public void onDeposit(InboundEvents.AccountMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.DEPOSIT_RECEIVED, NotificationTemplates.depositReceived(e.amount()), null, messageId);
    }

    @RabbitListener(queues = "notification.account.withdrawal.made.queue")
    public void onWithdrawal(InboundEvents.AccountMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.WITHDRAWAL_MADE, NotificationTemplates.withdrawalMade(e.amount()), null, messageId);
    }

    @RabbitListener(queues = "notification.account.pix.sent.queue")
    public void onPixSent(InboundEvents.AccountPixMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_SENT, NotificationTemplates.pixSent(e.amount()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.account.pix.received.queue")
    public void onPixReceived(InboundEvents.AccountPixMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_RECEIVED, NotificationTemplates.pixReceived(e.amount()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.account.pix.failed.queue")
    public void onPixFailed(InboundEvents.AccountPixFailed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_FAILED, NotificationTemplates.pixFailed(e.amount(), e.reason()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.account.pix.refund.sent.queue")
    public void onRefundSent(InboundEvents.AccountPixMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_REFUND_SENT, NotificationTemplates.refundSent(e.amount()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.account.pix.refund.received.queue")
    public void onRefundReceived(InboundEvents.AccountPixMovement e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_REFUND_RECEIVED, NotificationTemplates.refundReceived(e.amount()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.account.pix.refund.failed.queue")
    public void onRefundFailed(InboundEvents.AccountPixFailed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.PIX_REFUND_FAILED, NotificationTemplates.refundFailed(e.amount(), e.reason()), e.referenceId(), messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.closed.queue")
    public void onInvoiceClosed(InboundEvents.InvoiceClosed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_CLOSED,
                NotificationTemplates.invoiceClosed(e.referenceMonth(), e.total(), e.dueDate()), e.invoiceId(), messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.paid.queue")
    public void onInvoicePaid(InboundEvents.InvoicePaid e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_PAID,
                NotificationTemplates.invoicePaid(e.referenceMonth(), e.amount()), e.invoiceId(), messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.auto-debit-failed.queue")
    public void onAutoDebitFailed(InboundEvents.InvoiceAutoDebitFailed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_AUTO_DEBIT_FAILED,
                NotificationTemplates.autoDebitFailed(e.referenceMonth(), e.reason()), null, messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.partially-paid.queue")
    public void onInvoicePartiallyPaid(InboundEvents.InvoicePartiallyPaid e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_PARTIALLY_PAID, NotificationTemplates.invoicePartiallyPaid(
                e.referenceMonth(), e.amountPaid(), e.remaining(), e.minimumPayment(), e.totalPaid()), e.invoiceId(), messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.rolled-over.queue")
    public void onInvoiceRolledOver(InboundEvents.InvoiceRolledOver e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_ROLLED_OVER, NotificationTemplates.invoiceRolledOver(
                e.fromReferenceMonth(), e.toReferenceMonth(), e.carried(), e.charges(), e.late()), null, messageId);
    }

    @RabbitListener(queues = "notification.card.invoice.financed.queue")
    public void onInvoiceFinanced(InboundEvents.InvoiceFinanced e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.INVOICE_FINANCED, NotificationTemplates.invoiceFinanced(
                e.referenceMonth(), e.installments(), e.installmentAmount(), e.total(), e.automatic()), e.invoiceId(), messageId);
    }

    @RabbitListener(queues = "notification.card.dispute.opened.queue")
    public void onDisputeOpened(InboundEvents.DisputeOpened e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_DISPUTE_OPENED, NotificationTemplates.disputeOpened(
                e.description(), e.amount(), e.creditedAmount(), e.creditMonth(), e.cardBlocked(), e.virtualCardDeleted()),
                e.disputeId(), messageId);
    }

    @RabbitListener(queues = "notification.card.dispute.resolved.queue")
    public void onDisputeResolved(InboundEvents.DisputeResolved e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_DISPUTE_RESOLVED, NotificationTemplates.disputeResolved(
                e.description(), e.amount(), "ACCEPTED".equals(e.status()), e.resolutionNote(), e.reversalMonth(),
                e.interestRefunded()),
                e.disputeId(), messageId);
    }

    @RabbitListener(queues = "notification.card.purchase.approved.queue")
    public void onPurchaseApproved(InboundEvents.PurchaseApproved e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_PURCHASE_APPROVED, NotificationTemplates.purchaseApproved(
                e.description(), e.amount(), e.installments(), e.installmentAmount(), e.availableLimit(),
                e.virtualCardNickname()), e.purchaseId(), messageId);
    }

    @RabbitListener(queues = "notification.card.purchase.declined.queue")
    public void onPurchaseDeclined(InboundEvents.PurchaseDeclined e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_PURCHASE_DECLINED, NotificationTemplates.purchaseDeclined(
                e.description(), e.amount(), e.reason(), e.virtualCardNickname()), null, messageId);
    }

    @RabbitListener(queues = "notification.loan.contracted.queue")
    public void onLoanContracted(InboundEvents.LoanContracted e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_CONTRACTED, NotificationTemplates.loanContracted(e.released(),
                e.installments(), e.installmentAmount(), e.firstDueDate()), e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.installment.paid.queue")
    public void onLoanInstallmentPaid(InboundEvents.LoanInstallmentPaid e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_INSTALLMENT_PAID, NotificationTemplates.loanInstallmentPaid(
                e.number(), e.installments(), e.paid(), e.lateCharges(), e.discount(), e.remaining()), e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.installment.failed.queue")
    public void onLoanInstallmentFailed(InboundEvents.LoanInstallmentFailed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_INSTALLMENT_FAILED, NotificationTemplates.loanInstallmentFailed(
                e.number(), e.installments(), e.due(), e.reason()), e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.paid-off.queue")
    public void onLoanPaidOff(InboundEvents.LoanPaidOff e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_PAID_OFF, NotificationTemplates.loanPaidOff(e.paid(), e.discount()),
                e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.renegotiated.queue")
    public void onLoanRenegotiated(InboundEvents.LoanRenegotiated e,
                                   @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_RENEGOTIATED, NotificationTemplates.loanRenegotiated(e.debt(),
                e.downPayment(), e.installments(), e.installmentAmount(), e.firstDueDate()), e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.withdrawn.queue")
    public void onLoanWithdrawn(InboundEvents.LoanWithdrawn e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_WITHDRAWN, NotificationTemplates.loanWithdrawn(e.returned()),
                e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.loan.installment.due-soon.queue")
    public void onLoanInstallmentDueSoon(InboundEvents.LoanInstallmentDueSoon e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LOAN_INSTALLMENT_DUE_SOON, NotificationTemplates.loanInstallmentDueSoon(
                e.number(), e.installments(), e.amount(), e.dueDate()), e.loanId(), messageId);
    }

    @RabbitListener(queues = "notification.card.cashback.earned.queue")
    public void onCashbackEarned(InboundEvents.CashbackEarned e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_CASHBACK_EARNED,
                NotificationTemplates.cashbackEarned(e.referenceMonth(), e.amount(), "ACCOUNT".equals(e.destination())),
                null, messageId);
    }

    @RabbitListener(queues = "notification.card.due-day.changed.queue")
    public void onDueDayChanged(InboundEvents.DueDayChanged e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_DUE_DAY_CHANGED, NotificationTemplates.dueDayChanged(
                e.dueDay(), e.closingDay(), e.transitionMonth(), e.transitionDueDate(), e.transitionDays()), null, messageId);
    }

    @RabbitListener(queues = "notification.card.limit.usage-alert.queue")
    public void onLimitUsageAlert(InboundEvents.LimitUsageAlert e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_LIMIT_USAGE_ALERT, NotificationTemplates.limitUsageAlert(
                e.threshold(), e.usagePercent(), e.creditLimit(), e.approvedLimit(), e.availableLimit()), null, messageId);
    }

    @RabbitListener(queues = "notification.card.limit.offered.queue")
    public void onLimitOffered(InboundEvents.LimitOffered e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_LIMIT_OFFERED,
                NotificationTemplates.limitOffered(e.offeredLimit(), e.expiresAt()), e.offerId(), messageId);
    }

    @RabbitListener(queues = "notification.card.reissued.queue")
    public void onCardReissued(InboundEvents.CardReissued e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toCardOwner(e.cardId(), NotificationType.CARD_REISSUED,
                NotificationTemplates.cardReissued(e.lastDigits(), e.oldNumberCancelled()), e.plasticId(), messageId);
    }

    @RabbitListener(queues = "notification.pix.scheduled.failed.queue")
    public void onScheduledPixFailed(InboundEvents.ScheduledPixFailed e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.sourceAccountId(), NotificationType.PIX_SCHEDULED_FAILED,
                NotificationTemplates.scheduledPixFailed(e.amount(), e.scheduledDate(), e.reason()), e.transactionId(), messageId);
    }

    @RabbitListener(queues = "notification.pix.automatic.requested.queue")
    public void onPixAutomaticRequested(InboundEvents.PixAutomaticRequested e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.payerAccountId(), NotificationType.PIX_AUTOMATIC_REQUESTED, NotificationTemplates.pixAutomaticRequested(
                e.receiverName(), e.description(), e.frequency(), e.fixedAmount(), e.startDate()), e.authorizationId(), messageId);
    }

    @RabbitListener(queues = "notification.pix.automatic.charge.scheduled.queue")
    public void onPixAutomaticChargeScheduled(InboundEvents.PixAutomaticChargeScheduled e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.payerAccountId(), NotificationType.PIX_AUTOMATIC_CHARGE_SCHEDULED,
                NotificationTemplates.pixAutomaticChargeScheduled(e.receiverName(), e.description(), e.amount(), e.dueDate(),
                        e.attempt() == null ? 1 : e.attempt()),
                e.chargeId(), messageId);
    }

    @RabbitListener(queues = "notification.auth.password.changed.queue")
    public void onPasswordChanged(InboundEvents.PasswordChanged e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        deliver(Optional.of(e.customerId()), "cliente " + e.customerId(), NotificationType.PASSWORD_CHANGED,
                NotificationTemplates.passwordChanged("RESET".equals(e.reason())), null, messageId);
    }

    @RabbitListener(queues = "notification.account.limit.increase.requested.queue")
    public void onLimitIncreaseRequested(InboundEvents.LimitIncreaseRequested e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.accountId(), NotificationType.LIMIT_INCREASE_REQUESTED, NotificationTemplates.limitIncreaseRequested(
                e.limitType(), e.limitLabel(), e.requestedAmount(), e.effectiveAt()), e.requestId(), messageId);
    }

    @RabbitListener(queues = "notification.auth.device.trusted.queue")
    public void onDeviceTrusted(InboundEvents.DeviceTrusted e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        deliver(Optional.of(e.customerId()), "cliente " + e.customerId(), NotificationType.NEW_DEVICE_LOGIN,
                NotificationTemplates.newDeviceLogin(e.userAgent(), e.occurredAt()), null, messageId);
    }

    @RabbitListener(queues = "notification.savings.goal.reached.queue")
    public void onSavingsGoalReached(InboundEvents.SavingsGoalReached e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        deliver(Optional.of(e.customerId()), "cliente " + e.customerId(), NotificationType.SAVINGS_GOAL_REACHED,
                NotificationTemplates.savingsGoalReached(e.name(), e.goalAmount()), e.boxId(), messageId);
    }

    @RabbitListener(queues = "notification.boleto.paid.queue")
    public void onBoletoPaid(InboundEvents.BoletoPaid e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.beneficiaryAccountId(), NotificationType.BOLETO_RECEIVED, NotificationTemplates.boletoReceived(
                e.payerName(), e.paidAmount(), "COMPENSACAO".equals(e.via())), e.boletoId(), messageId);
    }

    @RabbitListener(queues = "notification.boleto.payment.made.queue")
    public void onBoletoPaymentMade(InboundEvents.BoletoPaymentMade e, @Header(name = AmqpHeaders.MESSAGE_ID, required = false) String messageId) {
        toAccountOwner(e.payerAccountId(), NotificationType.BOLETO_PAYMENT_MADE,
                NotificationTemplates.boletoPaymentMade(e.paidAmount(), e.beneficiaryName()), e.paymentId(), messageId);
    }

    private void toAccountOwner(UUID accountId, NotificationType type, NotificationTemplates.Text text,
                                UUID referenceId, String messageId) {
        deliver(recipients.customerOfAccount(accountId), "conta " + accountId, type, text, referenceId, messageId);
    }

    private void toCardOwner(UUID cardId, NotificationType type, NotificationTemplates.Text text,
                             UUID referenceId, String messageId) {
        deliver(recipients.customerOfCard(cardId), "cartão " + cardId, type, text, referenceId, messageId);
    }

    private void deliver(Optional<UUID> customerId, String resource, NotificationType type, NotificationTemplates.Text text,
                         UUID referenceId, String messageId) {
        if (customerId.isEmpty()) {
            // recurso sumiu: não há a quem avisar, e retry não muda isso
            logger.warn("Notificação {} descartada: {} não encontrado", type, resource);
            return;
        }
        String sourceEventId = messageId != null ? messageId : UUID.randomUUID().toString();
        notify.execute(new Notify.Input(customerId.get(), type, text, referenceId, sourceEventId));
    }
}
