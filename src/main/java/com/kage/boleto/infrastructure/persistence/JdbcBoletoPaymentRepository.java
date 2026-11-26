package com.kage.boleto.infrastructure.persistence;

import com.kage.boleto.domain.repository.BoletoPaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcBoletoPaymentRepository implements BoletoPaymentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcBoletoPaymentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Payment p) {
        jdbcTemplate.update("INSERT INTO boleto_payments (id, payer_account_id, barcode, bank_code, kind, boleto_id, "
                        + "beneficiary_name, due_date, face_amount, fine, interest, paid_amount, paid_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.id(), p.payerAccountId(), p.barcode(), p.bankCode(), p.kind().name(), p.boletoId(), p.beneficiaryName(),
                p.dueDate() == null ? null : Date.valueOf(p.dueDate()), p.faceAmount(), p.fine(), p.interest(),
                p.paidAmount(), Timestamp.valueOf(p.paidAt()));
    }

    @Override
    public boolean existsByBarcode(String barcode) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM boleto_payments WHERE barcode = ?", Integer.class, barcode);
        return count != null && count > 0;
    }

    @Override
    public List<Payment> findByPayerAccount(UUID accountId, int limit) {
        return jdbcTemplate.query("SELECT * FROM boleto_payments WHERE payer_account_id = ? ORDER BY paid_at DESC LIMIT ?",
                MAPPER, accountId, limit);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jdbcTemplate.query("SELECT * FROM boleto_payments WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    private static final RowMapper<Payment> MAPPER = (rs, n) -> new Payment(rs.getObject("id", UUID.class),
            rs.getObject("payer_account_id", UUID.class), rs.getString("barcode"), rs.getString("bank_code"),
            Kind.valueOf(rs.getString("kind")), rs.getObject("boleto_id", UUID.class), rs.getString("beneficiary_name"),
            rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
            rs.getBigDecimal("face_amount"), rs.getBigDecimal("fine"), rs.getBigDecimal("interest"),
            rs.getBigDecimal("paid_amount"), rs.getTimestamp("paid_at").toLocalDateTime());
}
