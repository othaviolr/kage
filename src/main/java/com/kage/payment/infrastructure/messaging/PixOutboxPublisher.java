package com.kage.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class PixOutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(PixOutboxPublisher.class);

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public PixOutboxPublisher(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {
        List<OutboxRow> pending = jdbcTemplate.query(
                "SELECT id, payload, exchange, routing_key FROM pix_outbox WHERE published_at IS NULL ORDER BY created_at",
                (rs, rowNum) -> new OutboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("payload"),
                        rs.getString("exchange"),
                        rs.getString("routing_key")
                )
        );

        for (OutboxRow row : pending) {
            try {
                PixSentEvent event = objectMapper.readValue(row.payload(), PixSentEvent.class);
                rabbitTemplate.convertAndSend(row.exchange(), row.routingKey(), event);

                jdbcTemplate.update("UPDATE pix_outbox SET published_at = ? WHERE id = ?", Instant.now(), row.id());
            } catch (Exception e) {
                logger.warn("Falha ao publicar evento do outbox (id={}), tentando novamente na próxima execução", row.id(), e);
            }
        }
    }

    private record OutboxRow(UUID id, String payload, String exchange, String routingKey) {}
}