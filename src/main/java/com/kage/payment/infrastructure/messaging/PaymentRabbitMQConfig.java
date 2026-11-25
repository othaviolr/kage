package com.kage.payment.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

@Configuration
public class PaymentRabbitMQConfig {

    // exchange principal do PIX
    @Bean
    public TopicExchange pixExchange() {
        return new TopicExchange("pix.exchange");
    }

    // fila que o Account vai ouvir para debitar o saldo
    @Bean
    public Queue pixSentQueue() {
        return QueueBuilder.durable("pix.sent.queue")
                .deadLetterExchange("payment.dlx")
                .deadLetterRoutingKey("pix.sent.dlq")
                .build();
    }

    // fila que o Payment vai ouvir para confirmar a transação
    @Bean
    public Queue pixDebitConfirmedQueue() {
        return QueueBuilder.durable("pix.debit.confirmed.queue")
                .deadLetterExchange("payment.dlx")
                .deadLetterRoutingKey("pix.debit.confirmed.dlq")
                .build();
    }

    // fila que o Payment vai ouvir para marcar a transação como falha (débito/crédito recusado pelo Account)
    @Bean
    public Queue pixDebitFailedQueue() {
        return QueueBuilder.durable("pix.debit.failed.queue")
                .deadLetterExchange("payment.dlx")
                .deadLetterRoutingKey("pix.debit.failed.dlq")
                .build();
    }

    // binding: pix.exchange + routing key "pix.sent" → pix.sent.queue
    @Bean
    public Binding pixSentBinding() {
        return BindingBuilder.bind(pixSentQueue()).to(pixExchange()).with("pix.sent");
    }

    // binding: pix.exchange + routing key "pix.debit.confirmed" → pix.debit.confirmed.queue
    @Bean
    public Binding pixDebitConfirmedBinding() {
        return BindingBuilder.bind(pixDebitConfirmedQueue()).to(pixExchange()).with("pix.debit.confirmed");
    }

    // binding: pix.exchange + routing key "pix.debit.failed" → pix.debit.failed.queue
    @Bean
    public Binding pixDebitFailedBinding() {
        return BindingBuilder.bind(pixDebitFailedQueue()).to(pixExchange()).with("pix.debit.failed");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }

    @Bean
    public DirectExchange paymentDlx() {
        return new DirectExchange("payment.dlx");
    }

    @Bean
    public Queue pixSentDlq() {
        return QueueBuilder.durable("pix.sent.dlq").build();
    }

    @Bean
    public Binding pixSentDlqBinding() {
        return BindingBuilder.bind(pixSentDlq())
                .to(paymentDlx())
                .with("pix.sent.dlq");
    }

    @Bean
    public Queue pixDebitConfirmedDlq() {
        return QueueBuilder.durable("pix.debit.confirmed.dlq").build();
    }

    @Bean
    public Binding pixDebitConfirmedDlqBinding() {
        return BindingBuilder.bind(pixDebitConfirmedDlq())
                .to(paymentDlx())
                .with("pix.debit.confirmed.dlq");
    }

    @Bean
    public Queue pixDebitFailedDlq() {
        return QueueBuilder.durable("pix.debit.failed.dlq").build();
    }

    @Bean
    public Binding pixDebitFailedDlqBinding() {
        return BindingBuilder.bind(pixDebitFailedDlq())
                .to(paymentDlx())
                .with("pix.debit.failed.dlq");
    }
}