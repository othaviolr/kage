package com.kage.loan.infrastructure.persistence;

import com.kage.loan.domain.entity.Loan;
import com.kage.loan.domain.repository.LoanRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcLoanRepository implements LoanRepository {

    private static final String COLUMNS = "id, account_id, customer_id, released, iof, financed, monthly_rate, due_day, "
            + "cet_monthly, cet_yearly, status, contract_date, contracted_at, closed_at, origin, renegotiated_from, down_payment, "
            + "version";
    private static final String INSTALLMENT_COLUMNS = "number, due_date, amount, principal, interest, paid, paid_at, "
            + "paid_amount, late_charges, discount";

    private final JdbcTemplate jdbcTemplate;

    public JdbcLoanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Loan l) {
        jdbcTemplate.update("INSERT INTO loans (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                l.getId(), l.getAccountId(), l.getCustomerId(), l.getReleased().amount(), l.getIof().amount(),
                l.getFinanced().amount(), l.getMonthlyRate(), l.getDueDay(), l.getCetMonthly(), l.getCetYearly(),
                l.getStatus().name(), Date.valueOf(l.getContractDate()), Timestamp.valueOf(l.getContractedAt()),
                ts(l.getClosedAt()), l.getOrigin().name(), l.getRenegotiatedFrom(), amount(l.getDownPayment()));
        for (Loan.Installment i : l.getInstallments()) {
            jdbcTemplate.update("INSERT INTO loan_installments (loan_id, " + INSTALLMENT_COLUMNS + ") "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    l.getId(), i.number(), Date.valueOf(i.dueDate()), i.amount().amount(), i.principal().amount(),
                    i.interest().amount(), i.paid(), ts(i.paidAt()), amount(i.paidAmount()), amount(i.lateCharges()),
                    amount(i.discount()));
        }
    }

    @Override
    public void update(Loan l) {
        int updated = jdbcTemplate.update("UPDATE loans SET status = ?, closed_at = ?, version = version + 1 "
                + "WHERE id = ? AND version = ?", l.getStatus().name(), ts(l.getClosedAt()), l.getId(), l.getVersion());
        if (updated != 1) throw new OptimisticLockingFailureException("Empréstimo " + l.getId() + " mudou ao mesmo tempo");
        for (Loan.Installment i : l.getInstallments()) {
            jdbcTemplate.update("UPDATE loan_installments SET paid = ?, paid_at = ?, paid_amount = ?, late_charges = ?, "
                            + "discount = ? WHERE loan_id = ? AND number = ?",
                    i.paid(), ts(i.paidAt()), amount(i.paidAmount()), amount(i.lateCharges()), amount(i.discount()),
                    l.getId(), i.number());
        }
    }

    @Override
    public Optional<Loan> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM loans WHERE id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    @Override
    public List<Loan> findByAccountId(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM loans WHERE account_id = ? ORDER BY contracted_at DESC",
                (rs, n) -> map(rs), accountId);
    }

    @Override
    public Optional<Loan> findActiveByAccountId(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM loans WHERE account_id = ? AND status = 'ACTIVE'",
                (rs, n) -> map(rs), accountId).stream().findFirst();
    }

    @Override
    public List<UUID> activeWithDueUntil(LocalDate date) {
        return jdbcTemplate.queryForList("SELECT DISTINCT l.id FROM loans l JOIN loan_installments i ON i.loan_id = l.id "
                + "WHERE l.status = 'ACTIVE' AND NOT i.paid AND i.due_date <= ?", UUID.class, Date.valueOf(date));
    }

    @Override
    public List<InstallmentRef> notRemindedDueOn(LocalDate dueDate) {
        return jdbcTemplate.query("SELECT i.loan_id, i.number FROM loan_installments i JOIN loans l ON l.id = i.loan_id "
                        + "WHERE l.status = 'ACTIVE' AND NOT i.paid AND i.due_date = ? AND i.reminded_at IS NULL",
                (rs, n) -> new InstallmentRef(rs.getObject("loan_id", UUID.class), rs.getInt("number")), Date.valueOf(dueDate));
    }

    @Override
    public boolean markReminded(UUID loanId, int number, LocalDateTime at) {
        return jdbcTemplate.update("UPDATE loan_installments SET reminded_at = ? WHERE loan_id = ? AND number = ? "
                + "AND reminded_at IS NULL", Timestamp.valueOf(at), loanId, number) == 1;
    }

    @Override
    public boolean markFailureNotified(UUID loanId, int number, LocalDateTime at) {
        return jdbcTemplate.update("UPDATE loan_installments SET failure_notified_at = ? WHERE loan_id = ? AND number = ? "
                + "AND failure_notified_at IS NULL", Timestamp.valueOf(at), loanId, number) == 1;
    }

    private Loan map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Loan.Installment> installments = jdbcTemplate.query("SELECT " + INSTALLMENT_COLUMNS
                + " FROM loan_installments WHERE loan_id = ? ORDER BY number", (r, n) -> new Loan.Installment(
                r.getInt("number"), r.getDate("due_date").toLocalDate(), Money.of(r.getBigDecimal("amount")),
                Money.of(r.getBigDecimal("principal")), Money.of(r.getBigDecimal("interest")), r.getBoolean("paid"),
                local(r.getTimestamp("paid_at")), money(r.getBigDecimal("paid_amount")),
                money(r.getBigDecimal("late_charges")), money(r.getBigDecimal("discount"))), id);
        return Loan.reconstitute(id, rs.getObject("account_id", UUID.class), rs.getObject("customer_id", UUID.class),
                Money.of(rs.getBigDecimal("released")), Money.of(rs.getBigDecimal("iof")),
                Money.of(rs.getBigDecimal("financed")), rs.getBigDecimal("monthly_rate"), rs.getInt("due_day"),
                rs.getBigDecimal("cet_monthly"), rs.getBigDecimal("cet_yearly"), Loan.Status.valueOf(rs.getString("status")),
                rs.getDate("contract_date").toLocalDate(), rs.getTimestamp("contracted_at").toLocalDateTime(),
                local(rs.getTimestamp("closed_at")), installments, Loan.Origin.valueOf(rs.getString("origin")),
                rs.getObject("renegotiated_from", UUID.class), money(rs.getBigDecimal("down_payment")), rs.getLong("version"));
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static BigDecimal amount(Money value) {
        return value == null ? null : value.amount();
    }

    private static Money money(BigDecimal value) {
        return value == null ? null : Money.of(value);
    }
}
