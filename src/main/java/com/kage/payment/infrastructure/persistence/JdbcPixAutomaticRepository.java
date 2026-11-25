package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Frequency;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Party;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Status;
import com.kage.payment.domain.entity.PixAutomaticCharge;
import com.kage.payment.domain.repository.PixAutomaticRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcPixAutomaticRepository implements PixAutomaticRepository {

    private static final String COLUMNS = "id, payer_account_id, receiver_account_id, receiver_pix_key, receiver_name, "
            + "description, contract_reference, frequency, fixed_amount, max_amount, start_date, end_date, status, "
            + "cancelled_by, created_at, updated_at, version";

    private static final String CHARGE_COLUMNS = "id, authorization_id, cycle, amount, due_date, pix_transaction_id, created_at";

    private static final RowMapper<PixAutomaticAuthorization> MAPPER = (rs, n) -> PixAutomaticAuthorization.reconstitute(
            rs.getObject("id", UUID.class), rs.getObject("payer_account_id", UUID.class),
            rs.getObject("receiver_account_id", UUID.class), rs.getString("receiver_pix_key"), rs.getString("receiver_name"),
            rs.getString("description"), rs.getString("contract_reference"), Frequency.valueOf(rs.getString("frequency")),
            money(rs.getBigDecimal("fixed_amount")), money(rs.getBigDecimal("max_amount")),
            rs.getDate("start_date").toLocalDate(), rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate(),
            Status.valueOf(rs.getString("status")),
            rs.getString("cancelled_by") == null ? null : Party.valueOf(rs.getString("cancelled_by")),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getLong("version"));

    private static final RowMapper<PixAutomaticCharge> CHARGE_MAPPER = (rs, n) -> new PixAutomaticCharge(
            rs.getObject("id", UUID.class), rs.getObject("authorization_id", UUID.class), rs.getInt("cycle"),
            Money.of(rs.getBigDecimal("amount")), rs.getDate("due_date").toLocalDate(),
            rs.getObject("pix_transaction_id", UUID.class), rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcPixAutomaticRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(PixAutomaticAuthorization a) {
        jdbcTemplate.update("INSERT INTO pix_automatic_authorizations (" + COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                a.getId(), a.getPayerAccountId(), a.getReceiverAccountId(), a.getReceiverPixKey(), a.getReceiverName(),
                a.getDescription(), a.getContractReference(), a.getFrequency().name(), amount(a.getFixedAmount()),
                amount(a.getMaxAmount()), Date.valueOf(a.getStartDate()),
                a.getEndDate() == null ? null : Date.valueOf(a.getEndDate()), a.getStatus().name(),
                a.getCancelledBy() == null ? null : a.getCancelledBy().name(), Timestamp.valueOf(a.getCreatedAt()),
                Timestamp.valueOf(a.getUpdatedAt()));
    }

    @Override
    public void update(PixAutomaticAuthorization a) {
        int updated = jdbcTemplate.update("""
                        UPDATE pix_automatic_authorizations
                        SET max_amount = ?, status = ?, cancelled_by = ?, updated_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                amount(a.getMaxAmount()), a.getStatus().name(), a.getCancelledBy() == null ? null : a.getCancelledBy().name(),
                Timestamp.valueOf(a.getUpdatedAt()), a.getId(), a.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Autorização " + a.getId() + " foi alterada por outra operação");
        }
    }

    @Override
    public Optional<PixAutomaticAuthorization> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_automatic_authorizations WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<PixAutomaticAuthorization> findByAccount(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM pix_automatic_authorizations "
                + "WHERE payer_account_id = ? OR receiver_account_id = ? ORDER BY created_at DESC", MAPPER, accountId, accountId);
    }

    @Override
    public void insertCharge(PixAutomaticCharge c) {
        jdbcTemplate.update("INSERT INTO pix_automatic_charges (" + CHARGE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                c.id(), c.authorizationId(), c.cycle(), c.amount().amount(), Date.valueOf(c.dueDate()),
                c.pixTransactionId(), Timestamp.valueOf(c.createdAt()));
    }

    @Override
    public Optional<PixAutomaticCharge> findChargeById(UUID id) {
        return jdbcTemplate.query("SELECT " + CHARGE_COLUMNS + " FROM pix_automatic_charges WHERE id = ?", CHARGE_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<PixAutomaticCharge> findCharges(UUID authorizationId) {
        return jdbcTemplate.query("SELECT " + CHARGE_COLUMNS + " FROM pix_automatic_charges WHERE authorization_id = ? "
                + "ORDER BY cycle", CHARGE_MAPPER, authorizationId);
    }

    private static Money money(BigDecimal value) {
        return value == null ? null : Money.of(value);
    }

    private static BigDecimal amount(Money value) {
        return value == null ? null : value.amount();
    }
}
