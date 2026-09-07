package com.kage.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kage.payment.application.usecase.PixEventPublisher;
import com.kage.payment.application.usecase.SendPix;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

public class OutboxPixEventPublisher implements PixEventPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPixEventPublisher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishPixSent(SendPix.Output output) {
        PixSentEvent event = new PixSentEvent(output.transactionId(), output.sourceAccountId(),
                output.targetPixKey(), output.targetAccountId(), output.amount(), output.status(), output.e2eId(), output.createdAt());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao serializar PixSentEvent para o outbox", e);
        }

        jdbcTemplate.update(
                "INSERT INTO pix_outbox (id, payload, exchange, routing_key, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), payload, "pix.exchange", "pix.sent", Instant.now()
        );
    }
}