package com.kage.auth.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** O auth escuta customer.blocked (customer.exchange) para derrubar as sessões do cliente. */
@Configuration
public class AuthRabbitMQConfig {

    static final String CUSTOMER_BLOCKED_QUEUE = "auth.customer.blocked.queue";
    static final String DLX = "auth.dlx";

    @Bean
    public DirectExchange authDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Declarables authQueues(DirectExchange authDlx) {
        Queue queue = QueueBuilder.durable(CUSTOMER_BLOCKED_QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey("auth.customer.blocked.dlq")
                .build();
        Queue dlq = QueueBuilder.durable("auth.customer.blocked.dlq").build();
        return new Declarables(
                queue,
                new Binding(CUSTOMER_BLOCKED_QUEUE, Binding.DestinationType.QUEUE, "customer.exchange", "customer.blocked", null),
                dlq,
                BindingBuilder.bind(dlq).to(authDlx).with("auth.customer.blocked.dlq"));
    }
}
