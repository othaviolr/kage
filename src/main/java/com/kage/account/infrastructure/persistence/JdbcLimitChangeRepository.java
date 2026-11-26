package com.kage.account.infrastructure.persistence;

import com.kage.account.domain.enums.LimitType;
import com.kage.account.domain.repository.LimitChangeRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class JdbcLimitChangeRepository implements LimitChangeRepository {

    private static final String COLUMNS = "id, account_id, limit_type, previous_amount, requested_amount, requested_at, "
            + "effective_at, status";

    private static final RowMapper<LimitChangeRequest> MAPPER = (rs, n) -> new LimitChangeRequest(
            rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), LimitType.valueOf(rs.getString("limit_type")),
            Money.of(rs.getBigDecimal("previous_amount")), Money.of(rs.getBigDecimal("requested_amount")),
            rs.getTimestamp("requested_at").toLocalDateTime(), rs.getTimestamp("effective_at").toLocalDateTime(),
            Status.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcLimitChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(LimitChangeRequest r) {
        jdbcTemplate.update("INSERT INTO account_limit_requests (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.accountId(), r.type().name(), r.previousAmount().amount(), r.requestedAmount().amount(),
                Timestamp.valueOf(r.requestedAt()), Timestamp.valueOf(r.effectiveAt()), r.status().name());
    }

    @Override
    public List<LimitChangeRequest> findPending(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM account_limit_requests WHERE account_id = ? "
                + "AND status = 'PENDING' ORDER BY effective_at", MAPPER, accountId);
    }

    @Override
    public List<LimitChangeRequest> findDue(UUID accountId, LocalDateTime now) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM account_limit_requests WHERE account_id = ? "
                + "AND status = 'PENDING' AND effective_at <= ? ORDER BY effective_at", MAPPER, accountId, Timestamp.valueOf(now));
    }

    @Override
    public List<UUID> accountsWithDue(LocalDateTime now, int limit) {
        return jdbcTemplate.queryForList("SELECT DISTINCT account_id FROM account_limit_requests "
                + "WHERE status = 'PENDING' AND effective_at <= ? LIMIT ?", UUID.class, Timestamp.valueOf(now), limit);
    }

    @Override
    public void markApplied(UUID requestId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE account_limit_requests SET status = 'APPLIED', resolved_at = ? WHERE id = ? "
                + "AND status = 'PENDING'", Timestamp.valueOf(now), requestId);
    }

    @Override
    public void replacePending(UUID accountId, LimitType type, LocalDateTime now) {
        jdbcTemplate.update("UPDATE account_limit_requests SET status = 'REPLACED', resolved_at = ? "
                + "WHERE account_id = ? AND limit_type = ? AND status = 'PENDING'", Timestamp.valueOf(now), accountId, type.name());
    }

    @Override
    public boolean cancel(UUID requestId, UUID accountId, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE account_limit_requests SET status = 'CANCELLED', resolved_at = ? "
                + "WHERE id = ? AND account_id = ? AND status = 'PENDING'", Timestamp.valueOf(now), requestId, accountId) == 1;
    }
}
