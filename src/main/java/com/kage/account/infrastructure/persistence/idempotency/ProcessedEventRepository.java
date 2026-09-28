package com.kage.account.infrastructure.persistence.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * tenta reservar o evento como "sendo processado agora".
     * @return true se é a primeira vez (insert funcionou);
     *         false se já foi processado antes (PK duplicada = reentrega)
     */
    public boolean tryMarkAsProcessed(UUID eventId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO account_processed_events (event_id, processed_at) VALUES (?, ?)",
                    eventId, Timestamp.from(Instant.now())
            );
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}