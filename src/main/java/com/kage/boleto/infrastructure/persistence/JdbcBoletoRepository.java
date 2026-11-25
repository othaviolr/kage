package com.kage.boleto.infrastructure.persistence;

import com.kage.boleto.domain.entity.Boleto;
import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcBoletoRepository implements BoletoRepository {

    private static final String COLUMNS = "id, beneficiary_account_id, our_number, barcode, amount, due_date, payable_until, "
            + "fine_percent, monthly_interest_percent, payer_name, payer_document, description, status, paid_amount, paid_at, "
            + "paid_via, created_at, updated_at, version";

    private static final RowMapper<Boleto> MAPPER = (rs, n) -> Boleto.reconstitute(
            rs.getObject("id", UUID.class), rs.getObject("beneficiary_account_id", UUID.class), rs.getLong("our_number"),
            BoletoCode.parse(rs.getString("barcode")), Money.of(rs.getBigDecimal("amount")),
            rs.getDate("due_date").toLocalDate(), rs.getDate("payable_until").toLocalDate(),
            rs.getBigDecimal("fine_percent"), rs.getBigDecimal("monthly_interest_percent"), rs.getString("payer_name"),
            rs.getString("payer_document"), rs.getString("description"), Boleto.Status.valueOf(rs.getString("status")),
            rs.getBigDecimal("paid_amount") == null ? null : Money.of(rs.getBigDecimal("paid_amount")),
            toLocal(rs.getTimestamp("paid_at")), rs.getString("paid_via"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(), rs.getLong("version"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcBoletoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long nextOurNumber() {
        Long next = jdbcTemplate.queryForObject("SELECT nextval('boleto_our_number_seq')", Long.class);
        return next == null ? 1 : next;
    }

    @Override
    public void insert(Boleto b) {
        jdbcTemplate.update("INSERT INTO boletos (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                b.getId(), b.getBeneficiaryAccountId(), b.getOurNumber(), b.getCode().barcode(), b.getAmount().amount(),
                Date.valueOf(b.getDueDate()), Date.valueOf(b.getPayableUntil()), b.getFinePercent(),
                b.getMonthlyInterestPercent(), b.getPayerName(), b.getPayerDocument(), b.getDescription(),
                b.getStatus().name(), null, null, null, ts(b.getCreatedAt()), ts(b.getUpdatedAt()));
    }

    @Override
    public void update(Boleto b) {
        int updated = jdbcTemplate.update("""
                        UPDATE boletos SET status = ?, paid_amount = ?, paid_at = ?, paid_via = ?, updated_at = ?,
                               version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                b.getStatus().name(), b.getPaidAmount() == null ? null : b.getPaidAmount().amount(), ts(b.getPaidAt()),
                b.getPaidVia(), ts(b.getUpdatedAt()), b.getId(), b.getVersion());
        if (updated == 0) throw new OptimisticLockingFailureException("Boleto " + b.getId() + " foi alterado por outra operação");
    }

    @Override
    public Optional<Boleto> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM boletos WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Boleto> findByBarcode(String barcode) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM boletos WHERE barcode = ?", MAPPER, barcode).stream().findFirst();
    }

    @Override
    public List<Boleto> findByBeneficiaryAccount(UUID accountId, int limit) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM boletos WHERE beneficiary_account_id = ? "
                + "ORDER BY created_at DESC LIMIT ?", MAPPER, accountId, limit);
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocal(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
