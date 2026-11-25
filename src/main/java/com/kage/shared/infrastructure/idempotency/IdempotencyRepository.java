package com.kage.shared.infrastructure.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

/** Chaves de idempotência. Cada operação é um statement só (autocommit), fora da transação de negócio. */
public class IdempotencyRepository {

    public enum Status { IN_PROGRESS, COMPLETED }

    public record Entry(String owner, String key, String method, String path, String requestHash, Status status,
                        Integer responseStatus, String responseBody, LocalDateTime createdAt, LocalDateTime expiresAt) {}

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Reserva a chave (IN_PROGRESS). INSERT ... ON CONFLICT DO NOTHING: entre duas requisições
     * simultâneas com a mesma chave, exatamente uma consegue — a outra vê false.
     */
    public boolean tryReserve(String owner, String key, String method, String path, String requestHash,
                              LocalDateTime now, LocalDateTime expiresAt) {
        return jdbcTemplate.update("""
                        INSERT INTO idempotency_keys (owner, idempotency_key, request_method, request_path, request_hash,
                                                      status, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                        ON CONFLICT (owner, idempotency_key) DO NOTHING
                        """,
                owner, key, method, path, requestHash, Timestamp.valueOf(now), Timestamp.valueOf(expiresAt)) == 1;
    }

    public Optional<Entry> find(String owner, String key) {
        return jdbcTemplate.query("""
                        SELECT owner, idempotency_key, request_method, request_path, request_hash, status,
                               response_status, response_body, created_at, expires_at
                        FROM idempotency_keys WHERE owner = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new Entry(rs.getString("owner"), rs.getString("idempotency_key"),
                        rs.getString("request_method"), rs.getString("request_path"), rs.getString("request_hash"),
                        Status.valueOf(rs.getString("status")), (Integer) rs.getObject("response_status"),
                        rs.getString("response_body"), rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("expires_at").toLocalDateTime()),
                owner, key).stream().findFirst();
    }

    public void complete(String owner, String key, int responseStatus, String responseBody) {
        jdbcTemplate.update("UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?, response_body = ? "
                + "WHERE owner = ? AND idempotency_key = ?", responseStatus, responseBody, owner, key);
    }

    /** Libera a chave (erro 5xx: nada foi garantido, o cliente pode tentar de novo). */
    public void release(String owner, String key) {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE owner = ? AND idempotency_key = ?", owner, key);
    }

    /** Chave vencida pode ser reaproveitada: apaga antes de reservar. */
    public void deleteIfExpired(String owner, String key, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM idempotency_keys WHERE owner = ? AND idempotency_key = ? AND expires_at <= ?",
                owner, key, Timestamp.valueOf(now));
    }

    public int deleteExpired(LocalDateTime now) {
        return jdbcTemplate.update("DELETE FROM idempotency_keys WHERE expires_at <= ?", Timestamp.valueOf(now));
    }
}
