package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.repository.LimitAlertRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

public class JdbcLimitAlertRepository implements LimitAlertRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLimitAlertRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markAlerted(UUID cardId, YearMonth referenceMonth, int threshold, LocalDateTime alertedAt) {
        return jdbcTemplate.update("""
                        INSERT INTO card_limit_alerts (card_id, reference_month, threshold, alerted_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (card_id, reference_month, threshold) DO NOTHING
                        """,
                cardId, referenceMonth.toString(), threshold, Timestamp.valueOf(alertedAt)) == 1;
    }
}
