package com.kage.auth.infrastructure.persistence;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.SessionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SessionRepositoryImpl implements SessionRepository {

    private static final String COLUMNS = "id, subject, customer_id, role, user_agent, created_at, last_refreshed_at, "
            + "expires_at, revoked_at, revoke_reason";

    private static final RowMapper<Session> MAPPER = (rs, rowNum) -> Session.reconstitute(
            rs.getObject("id", UUID.class),
            rs.getString("subject"),
            rs.getObject("customer_id", UUID.class),
            Session.Role.valueOf(rs.getString("role")),
            rs.getString("user_agent"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("last_refreshed_at").toLocalDateTime(),
            rs.getTimestamp("expires_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("revoked_at")),
            rs.getString("revoke_reason"));

    private final JdbcTemplate jdbcTemplate;

    public SessionRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Session s) {
        jdbcTemplate.update("INSERT INTO auth_sessions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.getId(), s.getSubject(), s.getCustomerId(), s.getRole().name(), s.getUserAgent(),
                ts(s.getCreatedAt()), ts(s.getLastRefreshedAt()), ts(s.getExpiresAt()), ts(s.getRevokedAt()),
                s.getRevokeReason());
    }

    @Override
    public void update(Session s) {
        jdbcTemplate.update("UPDATE auth_sessions SET last_refreshed_at = ?, expires_at = ?, revoked_at = ?, revoke_reason = ? "
                        + "WHERE id = ?",
                ts(s.getLastRefreshedAt()), ts(s.getExpiresAt()), ts(s.getRevokedAt()), s.getRevokeReason(), s.getId());
    }

    @Override
    public Optional<Session> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM auth_sessions WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public boolean isActive(UUID sessionId, LocalDateTime now) {
        Integer found = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ?",
                Integer.class, sessionId, ts(now));
        return found != null && found > 0;
    }

    @Override
    public List<Session> findActiveByCustomer(UUID customerId, LocalDateTime now) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM auth_sessions "
                        + "WHERE customer_id = ? AND revoked_at IS NULL AND expires_at > ? ORDER BY last_refreshed_at DESC",
                MAPPER, customerId, ts(now));
    }

    @Override
    public int revokeAllByCustomer(UUID customerId, String reason, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE auth_sessions SET revoked_at = ?, revoke_reason = ? "
                + "WHERE customer_id = ? AND revoked_at IS NULL", ts(now), reason, customerId);
    }

    @Override
    public void insertRefreshToken(String tokenHash, UUID sessionId, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO auth_refresh_tokens (token_hash, session_id, issued_at, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, sessionId, ts(issuedAt), ts(expiresAt));
    }

    @Override
    public Optional<RefreshTokenRecord> findRefreshToken(String tokenHash) {
        return jdbcTemplate.query("SELECT token_hash, session_id, expires_at, used_at FROM auth_refresh_tokens WHERE token_hash = ?",
                (rs, rowNum) -> new RefreshTokenRecord(rs.getString("token_hash"), rs.getObject("session_id", UUID.class),
                        rs.getTimestamp("expires_at").toLocalDateTime(), toLocal(rs.getTimestamp("used_at"))),
                tokenHash).stream().findFirst();
    }

    @Override
    public boolean markRefreshTokenUsed(String tokenHash, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE auth_refresh_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL",
                ts(now), tokenHash) == 1;
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocal(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
