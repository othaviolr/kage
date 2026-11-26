package com.kage.auth.infrastructure.persistence;

import com.kage.auth.domain.repository.DeviceVerificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DeviceVerificationRepositoryImpl implements DeviceVerificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeviceVerificationRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean touchTrusted(UUID customerId, String deviceHash, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE trusted_devices SET last_seen_at = ? "
                        + "WHERE customer_id = ? AND device_hash = ? AND revoked_at IS NULL",
                Timestamp.valueOf(now), customerId, deviceHash) == 1;
    }

    @Override
    public void trust(UUID customerId, String deviceHash, String userAgent, LocalDateTime now) {
        jdbcTemplate.update("""
                        INSERT INTO trusted_devices (id, customer_id, device_hash, user_agent, trusted_at, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (customer_id, device_hash) WHERE revoked_at IS NULL
                        DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                        """,
                UUID.randomUUID(), customerId, deviceHash, truncate(userAgent), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    @Override
    public List<TrustedDevice> listTrusted(UUID customerId) {
        return jdbcTemplate.query("SELECT id, user_agent, trusted_at, last_seen_at FROM trusted_devices "
                        + "WHERE customer_id = ? AND revoked_at IS NULL ORDER BY last_seen_at DESC",
                (rs, n) -> new TrustedDevice(rs.getObject("id", UUID.class), rs.getString("user_agent"),
                        rs.getTimestamp("trusted_at").toLocalDateTime(), rs.getTimestamp("last_seen_at").toLocalDateTime()),
                customerId);
    }

    @Override
    public boolean revokeTrusted(UUID deviceId, UUID customerId, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE trusted_devices SET revoked_at = ? WHERE id = ? AND customer_id = ? "
                + "AND revoked_at IS NULL", Timestamp.valueOf(now), deviceId, customerId) == 1;
    }

    @Override
    public int revokeAllTrusted(UUID customerId, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE trusted_devices SET revoked_at = ? WHERE customer_id = ? AND revoked_at IS NULL",
                Timestamp.valueOf(now), customerId);
    }

    @Override
    public void insertChallenge(Challenge c) {
        jdbcTemplate.update("INSERT INTO login_challenges (id, customer_id, code_hash, device_hash, user_agent, created_at, "
                        + "expires_at, attempts) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                c.id(), c.customerId(), c.codeHash(), c.deviceHash(), truncate(c.userAgent()),
                Timestamp.valueOf(c.createdAt()), Timestamp.valueOf(c.expiresAt()));
    }

    @Override
    public Optional<Challenge> findChallenge(UUID id) {
        return jdbcTemplate.query("SELECT id, customer_id, code_hash, device_hash, user_agent, created_at, expires_at, "
                        + "attempts, used_at FROM login_challenges WHERE id = ?",
                (rs, n) -> new Challenge(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("code_hash"), rs.getString("device_hash"), rs.getString("user_agent"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("expires_at").toLocalDateTime(),
                        rs.getInt("attempts"),
                        rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toLocalDateTime()),
                id).stream().findFirst();
    }

    @Override
    public void registerFailedAttempt(UUID challengeId) {
        jdbcTemplate.update("UPDATE login_challenges SET attempts = attempts + 1 WHERE id = ?", challengeId);
    }

    @Override
    public boolean markChallengeUsed(UUID challengeId, LocalDateTime now) {
        return jdbcTemplate.update("UPDATE login_challenges SET used_at = ? WHERE id = ? AND used_at IS NULL",
                Timestamp.valueOf(now), challengeId) == 1;
    }

    @Override
    public int countChallengesSince(UUID customerId, LocalDateTime since) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM login_challenges WHERE customer_id = ? AND created_at >= ?",
                Integer.class, customerId, Timestamp.valueOf(since));
        return count == null ? 0 : count;
    }

    @Override
    public void invalidatePendingChallenges(UUID customerId, LocalDateTime now) {
        jdbcTemplate.update("UPDATE login_challenges SET used_at = ? WHERE customer_id = ? AND used_at IS NULL",
                Timestamp.valueOf(now), customerId);
    }

    private static String truncate(String userAgent) {
        return userAgent == null || userAgent.length() <= 255 ? userAgent : userAgent.substring(0, 255);
    }
}
