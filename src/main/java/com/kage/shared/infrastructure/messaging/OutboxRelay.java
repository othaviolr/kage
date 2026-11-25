package com.kage.shared.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entrega no RabbitMQ os eventos gravados pelo {@link OutboxEventPublisher}, em ordem de criação.
 *
 * - FOR UPDATE SKIP LOCKED: com mais de uma instância da app rodando, cada uma pega um lote
 *   diferente em vez de as duas publicarem o mesmo evento.
 * - A mensagem vai com o JSON cru, sem header de tipo: o listener converte pelo tipo do parâmetro
 *   (cada contexto tem a sua própria classe do evento).
 * - Entrega é at-least-once (se o commit falhar depois do send, o evento sai de novo na próxima
 *   rodada); por isso todos os consumers são idempotentes (Inbox).
 */
@Component
public class OutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${kage.outbox.relay-interval-ms:1000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxRow> pending = jdbcTemplate.query("""
                        SELECT id, exchange, routing_key, payload FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("exchange"),
                        rs.getString("routing_key"),
                        rs.getString("payload")
                ),
                BATCH_SIZE
        );

        for (OutboxRow row : pending) {
            try {
                rabbitTemplate.send(row.exchange(), row.routingKey(), toMessage(row));
                jdbcTemplate.update("UPDATE outbox_events SET published_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now()), row.id());
            } catch (Exception e) {
                // para no primeiro erro para não furar a ordem; o resto do lote sai na próxima rodada
                logger.warn("Falha ao publicar evento do outbox (id={}), tentando novamente na próxima execução", row.id(), e);
                return;
            }
        }
    }

    private Message toMessage(OutboxRow row) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(row.id().toString());
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), properties);
    }

    private record OutboxRow(UUID id, String exchange, String routingKey, String payload) {}
}
