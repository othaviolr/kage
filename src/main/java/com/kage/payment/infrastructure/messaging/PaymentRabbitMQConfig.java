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
        return QueueBuilder.durable("pix.sent.queue").build();
    }

    // fila que o Payment vai ouvir para confirmar a transação
    @Bean
    public Queue pixDebitConfirmedQueue() {
        return QueueBuilder.durable("pix.debit.confirmed.queue").build();
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
}