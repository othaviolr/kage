package com.kage.notification.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A notificação é só consumidora: declara as próprias filas e as liga aos exchanges de quem publica
 * (account.exchange, card.exchange). Cada routing key X ganha notification.X.queue, com dead-letter
 * para notification.X.dlq.
 */
@Configuration
public class NotificationRabbitMQConfig {

    static final String DLX = "notification.dlx";

    static final String ACCOUNT_EXCHANGE = "account.exchange";
    static final String CARD_EXCHANGE = "card.exchange";
    static final String AUTH_EXCHANGE = "auth.exchange";
    static final String PIX_EXCHANGE = "pix.exchange";
    static final String SAVINGS_EXCHANGE = "savings.exchange";
    static final String BOLETO_EXCHANGE = "boleto.exchange";
    static final String LOAN_EXCHANGE = "loan.exchange";

    /** routing key -> exchange de origem */
    static final Map<String, String> SUBSCRIPTIONS = Map.ofEntries(
            Map.entry("account.deposit.made", ACCOUNT_EXCHANGE),
            Map.entry("account.withdrawal.made", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.sent", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.received", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.failed", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.refund.sent", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.refund.received", ACCOUNT_EXCHANGE),
            Map.entry("account.pix.refund.failed", ACCOUNT_EXCHANGE),
            Map.entry("account.limit.increase.requested", ACCOUNT_EXCHANGE),
            Map.entry("card.invoice.closed", CARD_EXCHANGE),
            Map.entry("card.invoice.paid", CARD_EXCHANGE),
            Map.entry("card.invoice.auto-debit-failed", CARD_EXCHANGE),
            Map.entry("card.invoice.partially-paid", CARD_EXCHANGE),
            Map.entry("card.invoice.rolled-over", CARD_EXCHANGE),
            Map.entry("card.invoice.financed", CARD_EXCHANGE),
            Map.entry("card.dispute.opened", CARD_EXCHANGE),
            Map.entry("card.dispute.resolved", CARD_EXCHANGE),
            Map.entry("card.reissued", CARD_EXCHANGE),
            Map.entry("card.limit.offered", CARD_EXCHANGE),
            Map.entry("card.cashback.earned", CARD_EXCHANGE),
            Map.entry("card.limit.usage-alert", CARD_EXCHANGE),
            Map.entry("card.budget.alert", CARD_EXCHANGE),
            Map.entry("card.due-day.changed", CARD_EXCHANGE),
            Map.entry("card.purchase.approved", CARD_EXCHANGE),
            Map.entry("card.purchase.declined", CARD_EXCHANGE),
            Map.entry("auth.password.changed", AUTH_EXCHANGE),
            Map.entry("auth.device.trusted", AUTH_EXCHANGE),
            Map.entry("pix.scheduled.failed", PIX_EXCHANGE),
            Map.entry("pix.automatic.requested", PIX_EXCHANGE),
            Map.entry("pix.automatic.charge.scheduled", PIX_EXCHANGE),
            Map.entry("pix.charge.paid", PIX_EXCHANGE),
            Map.entry("savings.goal.reached", SAVINGS_EXCHANGE),
            Map.entry("boleto.paid", BOLETO_EXCHANGE),
            Map.entry("boleto.payment.made", BOLETO_EXCHANGE),
            Map.entry("loan.contracted", LOAN_EXCHANGE),
            Map.entry("loan.installment.paid", LOAN_EXCHANGE),
            Map.entry("loan.installment.failed", LOAN_EXCHANGE),
            Map.entry("loan.paid-off", LOAN_EXCHANGE),
            Map.entry("loan.withdrawn", LOAN_EXCHANGE),
            Map.entry("loan.renegotiated", LOAN_EXCHANGE),
            Map.entry("loan.installment.due-soon", LOAN_EXCHANGE)
    );

    /** Segunda fila no fechamento da fatura: o e-mail com o PDF, separado do push. */
    public static final String INVOICE_EMAIL_QUEUE = "notification.email.card.invoice.closed.queue";
    static final String INVOICE_EMAIL_DLQ = "notification.email.card.invoice.closed.dlq";

    static String queue(String routingKey) {
        return "notification." + routingKey + ".queue";
    }

    @Bean
    public DirectExchange notificationDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Declarables notificationQueues(DirectExchange notificationDlx) {
        List<Declarable> declarables = new ArrayList<>();
        SUBSCRIPTIONS.forEach((routingKey, exchange) -> {
            String dlqName = "notification." + routingKey + ".dlq";
            Queue queue = QueueBuilder.durable(queue(routingKey))
                    .deadLetterExchange(DLX)
                    .deadLetterRoutingKey(dlqName)
                    .build();
            Queue dlq = QueueBuilder.durable(dlqName).build();

            declarables.add(queue);
            declarables.add(new Binding(queue.getName(), Binding.DestinationType.QUEUE, exchange, routingKey, null));
            declarables.add(dlq);
            declarables.add(BindingBuilder.bind(dlq).to(notificationDlx).with(dlqName));
        });
        Queue emailQueue = QueueBuilder.durable(INVOICE_EMAIL_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(INVOICE_EMAIL_DLQ)
                .build();
        Queue emailDlq = QueueBuilder.durable(INVOICE_EMAIL_DLQ).build();
        declarables.add(emailQueue);
        declarables.add(new Binding(emailQueue.getName(), Binding.DestinationType.QUEUE, CARD_EXCHANGE,
                "card.invoice.closed", null));
        declarables.add(emailDlq);
        declarables.add(BindingBuilder.bind(emailDlq).to(notificationDlx).with(INVOICE_EMAIL_DLQ));
        return new Declarables(declarables);
    }
}
