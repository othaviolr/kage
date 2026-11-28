package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.repository.PixChargeRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcPixChargeRepository implements PixChargeRepository {

    private static final String COLUMNS =
            "id, account_id, pix_key, txid, amount, message, single_use, code, status, created_at, cancelled_at, expires_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcPixChargeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Charge c) {
        jdbcTemplate.update("INSERT INTO pix_charges (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.accountId(), c.pixKey(), c.txid(), c.amount(), c.message(), c.singleUse(), c.code(), c.status(),
                Timestamp.valueOf(c.createdAt()), ts(c.cancelledAt()), ts(c.expiresAt()));
    }

    @Override
    public Optional<Charge> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM pix_charges WHERE id = ?", id);
    }

    @Override
    public Optional<Charge> findByIdForUpdate(UUID id) {
        return one("SELECT " + COLUMNS + " FROM pix_charges WHERE id = ? FOR UPDATE", id);
    }

    @Override
    public Optional<Charge> findByTxid(String txid) {
        return one("SELECT " + COLUMNS + " FROM pix_charges WHERE txid = ?", txid);
    }

    @Override
    public List<Charge> findByAccountId(UUID accountId, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_charges WHERE account_id = ? "
                + "ORDER BY created_at DESC, id LIMIT ?", this::map, accountId, limit);
    }

    @Override
    public void cancel(UUID id, LocalDateTime at) {
        jdbcTemplate.update("UPDATE pix_charges SET status = 'CANCELLED', cancelled_at = ? WHERE id = ?",
                Timestamp.valueOf(at), id);
    }

    @Override
    public void insertPayment(Payment p) {
        jdbcTemplate.update("INSERT INTO pix_charge_payments (transaction_id, charge_id, payer_account_id, amount, paid_at) "
                + "VALUES (?, ?, ?, ?, ?)", p.transactionId(), p.chargeId(), p.payerAccountId(), p.amount(),
                Timestamp.valueOf(p.paidAt()));
    }

    @Override
    public List<Payment> paymentsOf(UUID chargeId) {
        return jdbcTemplate.query(PAYMENT_SELECT + "WHERE charge_id = ? ORDER BY paid_at, transaction_id",
                this::mapPayment, chargeId);
    }

    @Override
    public Optional<Payment> findPaymentByTransactionId(UUID transactionId) {
        return jdbcTemplate.query(PAYMENT_SELECT + "WHERE transaction_id = ?", this::mapPayment, transactionId)
                .stream().findFirst();
    }

    private static final String PAYMENT_SELECT =
            "SELECT transaction_id, charge_id, payer_account_id, amount, paid_at FROM pix_charge_payments ";

    private Payment mapPayment(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getObject("transaction_id", UUID.class), rs.getObject("charge_id", UUID.class),
                rs.getObject("payer_account_id", UUID.class), rs.getBigDecimal("amount"),
                rs.getTimestamp("paid_at").toLocalDateTime());
    }

    private static Timestamp ts(LocalDateTime at) {
        return at == null ? null : Timestamp.valueOf(at);
    }

    private Optional<Charge> one(String sql, Object arg) {
        return jdbcTemplate.query(sql, this::map, arg).stream().findFirst();
    }

    private Charge map(ResultSet rs, int row) throws SQLException {
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Charge(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("pix_key"),
                rs.getString("txid"), rs.getBigDecimal("amount"), rs.getString("message"), rs.getBoolean("single_use"),
                rs.getString("code"), rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime(),
                cancelled == null ? null : cancelled.toLocalDateTime(), expires == null ? null : expires.toLocalDateTime());
    }
}
