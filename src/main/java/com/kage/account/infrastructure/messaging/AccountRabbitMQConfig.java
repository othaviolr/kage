package com.kage.account.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AccountRabbitMQConfig {

    @Bean
    public TopicExchange accountExchange(){
        return new TopicExchange("account.exchange");
    }

    @Bean
    public Queue accountDepositMadeQueue(){
        return QueueBuilder.durable("account.deposit.made.queue").build();
    }

    @Bean
    public Binding accountDepositMadeBinding(){
        return BindingBuilder.bind(accountDepositMadeQueue()).to(accountExchange()).with("account.deposit.made");
    }
}
