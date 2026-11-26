package com.kage.payment.infrastructure.persistence.idempotency;

import com.kage.shared.domain.DomainTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryMarkAsProcessed(UUID eventId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO payment_processed_events (event_id, processed_at) VALUES (?, ?)",
                    eventId, DomainTime.now()
            );
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}