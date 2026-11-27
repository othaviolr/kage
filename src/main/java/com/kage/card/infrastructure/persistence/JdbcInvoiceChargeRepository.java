package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.InvoiceCharge;
import com.kage.card.domain.repository.InvoiceChargeRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcInvoiceChargeRepository implements InvoiceChargeRepository {

    private static final String COLUMNS = "id, card_id, invoice_id, reference_month, method, amount, expires_at, code, "
            + "reference, status, paid_at, created_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcInvoiceChargeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(InvoiceCharge c) {
        jdbcTemplate.update("INSERT INTO card_invoice_charges (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.getId(), c.getCardId(), c.getInvoiceId(), c.getReferenceMonth(), c.getMethod().name(),
                c.getAmount().amount(), Timestamp.valueOf(c.getExpiresAt()), c.getCode(), c.getReference(),
                c.getStatus().name(), c.getPaidAt() == null ? null : Timestamp.valueOf(c.getPaidAt()),
                Timestamp.valueOf(c.getCreatedAt()));
    }

    @Override
    public boolean update(InvoiceCharge c, InvoiceCharge.Status expectedStatus) {
        return jdbcTemplate.update("UPDATE card_invoice_charges SET status = ?, paid_at = ? WHERE id = ? AND status = ?",
                c.getStatus().name(), c.getPaidAt() == null ? null : Timestamp.valueOf(c.getPaidAt()), c.getId(),
                expectedStatus.name()) == 1;
    }

    @Override
    public Optional<InvoiceCharge> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_invoice_charges WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    @Override
    public List<InvoiceCharge> findByInvoiceId(UUID invoiceId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_invoice_charges WHERE invoice_id = ? "
                + "ORDER BY created_at DESC", this::map, invoiceId);
    }

    @Override
    public long nextOurNumber() {
        Long next = jdbcTemplate.queryForObject("SELECT nextval('card_invoice_charge_our_number_seq')", Long.class);
        return next == null ? 1 : next;
    }

    private InvoiceCharge map(ResultSet rs, int n) throws SQLException {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return InvoiceCharge.reconstitute(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getObject("invoice_id", UUID.class), rs.getString("reference_month"),
                InvoiceCharge.Method.valueOf(rs.getString("method")), Money.of(rs.getBigDecimal("amount")),
                rs.getTimestamp("expires_at").toLocalDateTime(), rs.getString("code"), rs.getString("reference"),
                InvoiceCharge.Status.valueOf(rs.getString("status")), paidAt == null ? null : paidAt.toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
