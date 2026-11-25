package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixRecurrence;
import com.kage.payment.domain.repository.PixRecurrenceRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcPixRecurrenceRepository implements PixRecurrenceRepository {

    private static final String COLUMNS = "id, source_account_id, target_pix_key, amount, description, frequency, "
            + "start_date, total_occurrences, occurrences_created, status, created_at, updated_at, version";

    private static final RowMapper<PixRecurrence> MAPPER = (rs, n) -> PixRecurrence.reconstitute(
            rs.getObject("id", UUID.class), rs.getObject("source_account_id", UUID.class), rs.getString("target_pix_key"),
            Money.of(rs.getBigDecimal("amount")), rs.getString("description"),
            PixRecurrence.Frequency.valueOf(rs.getString("frequency")), rs.getDate("start_date").toLocalDate(),
            rs.getInt("total_occurrences"), rs.getInt("occurrences_created"),
            PixRecurrence.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("version"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcPixRecurrenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(PixRecurrence r) {
        jdbcTemplate.update("INSERT INTO pix_recurrences (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                r.getId(), r.getSourceAccountId(), r.getTargetPixKey(), r.getAmount().amount(), r.getDescription(),
                r.getFrequency().name(), Date.valueOf(r.getStartDate()), r.getTotalOccurrences(),
                r.getOccurrencesCreated(), r.getStatus().name(), Timestamp.valueOf(r.getCreatedAt()),
                Timestamp.valueOf(r.getUpdatedAt()));
    }

    @Override
    public void update(PixRecurrence r) {
        int updated = jdbcTemplate.update("""
                        UPDATE pix_recurrences SET occurrences_created = ?, status = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                r.getOccurrencesCreated(), r.getStatus().name(), Timestamp.valueOf(r.getUpdatedAt()), r.getId(),
                r.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("PIX recorrente " + r.getId() + " foi alterado por outra operação");
        }
    }

    @Override
    public Optional<PixRecurrence> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_recurrences WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<PixRecurrence> findActiveByAccount(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_recurrences WHERE source_account_id = ? "
                + "AND status = 'ACTIVE' ORDER BY created_at DESC", MAPPER, accountId);
    }
}
