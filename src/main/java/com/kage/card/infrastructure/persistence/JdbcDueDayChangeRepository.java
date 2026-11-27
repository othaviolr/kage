package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.repository.DueDayChangeRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class JdbcDueDayChangeRepository implements DueDayChangeRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDueDayChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Change c) {
        jdbcTemplate.update("""
                        INSERT INTO card_due_day_changes (id, card_id, previous_closing_day, previous_due_day, closing_day,
                                                          due_day, changed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), c.cardId(), c.previousClosingDay(), c.previousDueDay(), c.closingDay(), c.dueDay(),
                Timestamp.valueOf(c.changedAt()));
    }

    @Override
    public Optional<LocalDateTime> lastChangeAt(UUID cardId) {
        return jdbcTemplate.query("SELECT MAX(changed_at) AS last FROM card_due_day_changes WHERE card_id = ?",
                (rs, n) -> rs.getTimestamp("last"), cardId).stream()
                .filter(java.util.Objects::nonNull).map(Timestamp::toLocalDateTime).findFirst();
    }
}
