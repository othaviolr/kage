package com.kage.auth.infrastructure.persistence;

import com.kage.auth.domain.entity.Credential;
import com.kage.auth.domain.repository.CredentialRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CredentialRepositoryImpl implements CredentialRepository {

    private final JdbcTemplate jdbcTemplate;

    public CredentialRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Credential credential) {
        jdbcTemplate.update("""
                        INSERT INTO customer_credentials (customer_id, password_hash, failed_attempts, locked_until, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                credential.getCustomerId(), credential.getPasswordHash(), credential.getFailedAttempts(),
                toTimestamp(credential), Timestamp.valueOf(credential.getCreatedAt()),
                Timestamp.valueOf(credential.getUpdatedAt()));
    }

    @Override
    public void update(Credential credential) {
        jdbcTemplate.update("""
                        UPDATE customer_credentials
                        SET password_hash = ?, failed_attempts = ?, locked_until = ?, updated_at = ?
                        WHERE customer_id = ?
                        """,
                credential.getPasswordHash(), credential.getFailedAttempts(), toTimestamp(credential),
                Timestamp.valueOf(credential.getUpdatedAt()), credential.getCustomerId());
    }

    @Override
    public Optional<Credential> findByCustomerId(UUID customerId) {
        List<Credential> found = jdbcTemplate.query("""
                        SELECT customer_id, password_hash, failed_attempts, locked_until, created_at, updated_at
                        FROM customer_credentials WHERE customer_id = ?
                        """,
                (rs, rowNum) -> Credential.reconstitute(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getInt("failed_attempts"),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toLocalDateTime(),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                customerId);
        return found.stream().findFirst();
    }

    private static Timestamp toTimestamp(Credential credential) {
        return credential.getLockedUntil() == null ? null : Timestamp.valueOf(credential.getLockedUntil());
    }
}
