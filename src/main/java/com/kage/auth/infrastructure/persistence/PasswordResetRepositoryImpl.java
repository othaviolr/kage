package com.kage.auth.infrastructure.persistence;

import com.kage.auth.domain.repository.PasswordResetRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class PasswordResetRepositoryImpl implements PasswordResetRepository {

    private final JdbcTemplate jdbcTemplate;

    public PasswordResetRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(String tokenHash, UUID customerId, LocalDateTime createdAt, LocalDateTime expiresAt) {
        jdbcTemplate.update("INSERT INTO password_reset_tokens (token_hash, customer_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                tokenHash, customerId, Timestamp.valueOf(createdAt), Timestamp.valueOf(expiresAt));
    }

    @Override
    public Optional<ResetToken> findByHash(String tokenHash) {
        return jdbcTemplate.query("SELECT token_hash, customer_id, expires_at, used_at FROM password_reset_tokens WHERE token_hash = ?",
                (rs, rowNum) -> new ResetToken(rs.getString("token_hash"), rs.getObject("customer_id", UUID.class),
                        rs.getTimestamp("expires_at").toLocalDateTime(),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toLocalDateTime()),
                tokenHash).stream().findFirst();
    }

    @Override
    public boolean markUsedIfUnused(String tokenHash, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE password_reset_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL",
                Timestamp.valueOf(now), tokenHash) == 1;
    }

    @Override
    public void invalidatePending(UUID customerId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE password_reset_tokens SET used_at = ? WHERE customer_id = ? AND used_at IS NULL",
                Timestamp.valueOf(now), customerId);
    }

    @Override
    public int countCreatedSince(UUID customerId, LocalDateTime since) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE customer_id = ? AND created_at >= ?",
                Integer.class, customerId, Timestamp.valueOf(since));
        return count == null ? 0 : count;
    }
}
