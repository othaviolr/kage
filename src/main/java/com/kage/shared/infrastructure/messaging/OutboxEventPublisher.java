package com.kage.shared.infrastructure.messaging;

import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Transactional Outbox: em vez de publicar no RabbitMQ no meio da regra de negócio, grava o evento
 * na tabela outbox_events NA MESMA transação da mudança de estado. Ou os dois são gravados, ou
 * nenhum — nunca existe saldo alterado sem evento, nem evento de algo que sofreu rollback. Quem
 * entrega de fato no broker é o {@link OutboxRelay}.
 *
 * Propagation.MANDATORY faz o erro aparecer cedo: chamar fora de uma transação é bug, não fallback.
 */
@Component
public class OutboxEventPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final MessageConverter messageConverter;

    public OutboxEventPublisher(JdbcTemplate jdbcTemplate, MessageConverter messageConverter) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageConverter = messageConverter;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String exchange, String routingKey, Object event) {
        // serializa com o mesmo conversor que os listeners usam para ler: um único formato de JSON
        byte[] body = messageConverter.toMessage(event, new MessageProperties()).getBody();

        jdbcTemplate.update(
                "INSERT INTO outbox_events (id, exchange, routing_key, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), exchange, routingKey, new String(body, StandardCharsets.UTF_8),
                Timestamp.from(Instant.now())
        );
    }
}
