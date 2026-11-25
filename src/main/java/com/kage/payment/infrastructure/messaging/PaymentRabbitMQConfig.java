package com.kage.payment.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Topologia do PIX. Toda routing key {@code X} ganha o mesmo kit: fila durável {@code X.queue} ligada
 * ao pix.exchange, com dead-letter para {@code X.dlq} no payment.dlx (mensagem que esgota os retries
 * não se perde, fica estacionada para análise).
 */
@Configuration
public class PaymentRabbitMQConfig {

    static final String PIX_EXCHANGE = "pix.exchange";
    static final String DLX = "payment.dlx";

    static final List<String> ROUTING_KEYS = List.of(
            "pix.sent",              // Payment -> Account: debitar origem, creditar destino
            "pix.debit.confirmed",   // Account -> Payment: PIX efetivado
            "pix.debit.failed",      // Account -> Payment: PIX recusado (saldo, conta bloqueada...)
            "pix.refund.approved",   // Payment -> Account: devolver o valor do estorno
            "pix.refund.completed",  // Account -> Payment: estorno efetivado
            "pix.refund.failed"      // Account -> Payment: estorno recusado
    );

    @Bean
    public TopicExchange pixExchange() {
        return new TopicExchange(PIX_EXCHANGE);
    }

    @Bean
    public DirectExchange paymentDlx() {
        return new DirectExchange(DLX);
    }

    @Bean
    public Declarables pixQueues(TopicExchange pixExchange, DirectExchange paymentDlx) {
        List<Declarable> declarables = new ArrayList<>();
        for (String routingKey : ROUTING_KEYS) {
            Queue queue = QueueBuilder.durable(routingKey + ".queue")
                    .deadLetterExchange(DLX)
                    .deadLetterRoutingKey(routingKey + ".dlq")
                    .build();
            Queue dlq = QueueBuilder.durable(routingKey + ".dlq").build();

            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(pixExchange).with(routingKey));
            declarables.add(dlq);
            declarables.add(BindingBuilder.bind(dlq).to(paymentDlx).with(routingKey + ".dlq"));
        }
        return new Declarables(declarables);
    }
}
