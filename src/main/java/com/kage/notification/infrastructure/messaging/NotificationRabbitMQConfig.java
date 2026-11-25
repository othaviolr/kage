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
            Map.entry("card.invoice.closed", CARD_EXCHANGE),
            Map.entry("card.invoice.paid", CARD_EXCHANGE),
            Map.entry("card.invoice.auto-debit-failed", CARD_EXCHANGE),
            Map.entry("card.invoice.partially-paid", CARD_EXCHANGE),
            Map.entry("card.invoice.rolled-over", CARD_EXCHANGE),
            Map.entry("card.invoice.financed", CARD_EXCHANGE),
            Map.entry("auth.password.changed", AUTH_EXCHANGE),
            Map.entry("pix.scheduled.failed", PIX_EXCHANGE),
            Map.entry("savings.goal.reached", SAVINGS_EXCHANGE),
            Map.entry("boleto.paid", BOLETO_EXCHANGE),
            Map.entry("boleto.payment.made", BOLETO_EXCHANGE)
    );

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
        return new Declarables(declarables);
    }
}
