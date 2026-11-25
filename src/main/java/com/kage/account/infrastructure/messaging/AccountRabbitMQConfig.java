package com.kage.account.infrastructure.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O Account publica seus eventos (account.deposit.made, account.withdrawal.made, account.pix.*) no
 * account.exchange e não declara filas: cada consumidor declara e liga a própria (ex.: notification).
 * Antes havia filas de depósito/saque sem consumidor nenhum, acumulando mensagens para sempre.
 */
@Configuration
public class AccountRabbitMQConfig {

    @Bean
    public TopicExchange accountExchange() {
        return new TopicExchange("account.exchange");
    }
}
