package com.kage.savings.infrastructure.report;

import com.kage.savings.application.query.IncomeReportSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consultas do informe direto nas tabelas (read model). Saldos históricos saem do balance_after do
 * último lançamento antes de cada corte — o ledger da conta e o extrato da caixinha já guardam isso.
 */
public class JdbcIncomeReportSource implements IncomeReportSource {

    private final JdbcTemplate jdbcTemplate;

    public JdbcIncomeReportSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Holder> holder(UUID customerId) {
        return jdbcTemplate.query("SELECT full_name, cpf FROM customers WHERE id = ?",
                (rs, n) -> new Holder(rs.getString("full_name"), rs.getString("cpf")), customerId).stream().findFirst();
    }

    @Override
    public List<AccountBalances> accounts(UUID customerId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                        SELECT a.id, a.branch, a.account_number || '-' || a.account_digit AS number, a.type,
                               COALESCE((SELECT e.balance_after FROM account_entries e
                                         WHERE e.account_id = a.id AND e.created_at < ?
                                         ORDER BY e.created_at DESC LIMIT 1), 0) AS before_balance,
                               COALESCE((SELECT e.balance_after FROM account_entries e
                                         WHERE e.account_id = a.id AND e.created_at < ?
                                         ORDER BY e.created_at DESC LIMIT 1), 0) AS cutoff_balance
                        FROM accounts a
                        WHERE a.customer_id = ? AND a.opened_at < ? AND (a.closed_at IS NULL OR a.closed_at >= ?)
                        ORDER BY a.opened_at
                        """,
                (rs, n) -> new AccountBalances(rs.getObject("id", UUID.class), rs.getString("branch"), rs.getString("number"),
                        rs.getString("type"), rs.getBigDecimal("before_balance"), rs.getBigDecimal("cutoff_balance")),
                ts(from), ts(to), customerId, ts(to), ts(from));
    }

    @Override
    public List<BoxFigures> savingsBoxes(UUID customerId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                        SELECT b.id, b.name, b.status,
                               COALESCE((SELECT m.balance_after FROM savings_box_movements m
                                         WHERE m.box_id = b.id AND m.created_at < ?
                                         ORDER BY m.created_at DESC LIMIT 1), 0) AS before_balance,
                               COALESCE((SELECT m.balance_after FROM savings_box_movements m
                                         WHERE m.box_id = b.id AND m.created_at < ?
                                         ORDER BY m.created_at DESC LIMIT 1), 0) AS cutoff_balance,
                               COALESCE(r.gain, 0) AS gain, COALESCE(r.iof, 0) AS iof, COALESCE(r.income_tax, 0) AS income_tax
                        FROM savings_boxes b
                        LEFT JOIN (SELECT box_id, SUM(gain) AS gain, SUM(iof) AS iof, SUM(income_tax) AS income_tax
                                   FROM savings_box_movements
                                   WHERE type = 'WITHDRAWAL' AND created_at >= ? AND created_at < ?
                                   GROUP BY box_id) r ON r.box_id = b.id
                        WHERE b.customer_id = ? AND b.created_at < ? AND (b.closed_at IS NULL OR b.closed_at >= ?)
                        ORDER BY b.created_at
                        """,
                (rs, n) -> new BoxFigures(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                        rs.getBigDecimal("before_balance"), rs.getBigDecimal("cutoff_balance"), rs.getBigDecimal("gain"),
                        rs.getBigDecimal("iof"), rs.getBigDecimal("income_tax")),
                ts(from), ts(to), ts(from), ts(to), customerId, ts(to), ts(from));
    }

    private static Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }
}
