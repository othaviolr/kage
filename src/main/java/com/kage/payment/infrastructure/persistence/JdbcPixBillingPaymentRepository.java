package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.repository.PixBillingPaymentRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class JdbcPixBillingPaymentRepository implements PixBillingPaymentRepository {

    private static final String COLUMNS = "id, account_id, charge_id, kind, code, beneficiary, description, amount, paid_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcPixBillingPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Payment p) {
        jdbcTemplate.update("INSERT INTO pix_billing_payments (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.id(), p.accountId(), p.chargeId(), p.kind(), p.code(), p.beneficiary(), p.description(), p.amount(),
                Timestamp.valueOf(p.paidAt()));
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_billing_payments WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    @Override
    public Money sumPaidSince(UUID accountId, LocalDateTime since) {
        BigDecimal sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM pix_billing_payments WHERE account_id = ? AND paid_at >= ?",
                BigDecimal.class, accountId, Timestamp.valueOf(since));
        return Money.of(sum);
    }

    private Payment map(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("charge_id", UUID.class), rs.getString("kind"), rs.getString("code"),
                rs.getString("beneficiary"), rs.getString("description"), rs.getBigDecimal("amount"),
                rs.getTimestamp("paid_at").toLocalDateTime());
    }
}
