package com.kage.notification.infrastructure.messaging;

import com.kage.notification.application.usecase.NotificationTemplates;
import com.kage.notification.application.usecase.Notify;
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

    public NotificationEventConsumer(Notify notify, RecipientDirectory recipients) {
        this.notify = notify;
        this.recipients = recipients;
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
