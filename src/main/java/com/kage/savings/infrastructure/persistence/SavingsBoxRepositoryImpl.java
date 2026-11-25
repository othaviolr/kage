package com.kage.savings.infrastructure.persistence;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.savings.domain.entity.SavingsLot;
import com.kage.savings.domain.repository.SavingsBoxRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC com locking otimista manual: UPDATE ... WHERE version = ? que não acha a linha = alguém mudou
 * antes. Os lotes são parte da caixinha (agregado): gravados por inteiro a cada update, na mesma
 * transação e sob a mesma checagem de versão.
 */
public class SavingsBoxRepositoryImpl implements SavingsBoxRepository {

    private static final String COLUMNS = "id, customer_id, account_id, name, goal_amount, balance, total_yield, "
            + "status, last_yield_date, goal_reached_at, created_at, updated_at, closed_at, version";

    private final JdbcTemplate jdbcTemplate;

    public SavingsBoxRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(SavingsBox b) {
        jdbcTemplate.update("INSERT INTO savings_boxes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                b.getId(), b.getCustomerId(), b.getAccountId(), b.getName(), amount(b.getGoalAmount()),
                b.getBalance().amount(), b.getTotalYield().amount(), b.getStatus().name(),
                Date.valueOf(b.getLastYieldDate()), ts(b.getGoalReachedAt()), ts(b.getCreatedAt()), ts(b.getUpdatedAt()),
                ts(b.getClosedAt()));
        writeLots(b);
    }

    @Override
    public void update(SavingsBox b) {
        int updated = jdbcTemplate.update("""
                        UPDATE savings_boxes SET balance = ?, total_yield = ?, status = ?, last_yield_date = ?,
                               goal_reached_at = ?, updated_at = ?, closed_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                b.getBalance().amount(), b.getTotalYield().amount(), b.getStatus().name(),
                Date.valueOf(b.getLastYieldDate()), ts(b.getGoalReachedAt()), ts(b.getUpdatedAt()), ts(b.getClosedAt()),
                b.getId(), b.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Caixinha " + b.getId() + " foi alterada por outra operação");
        }
        jdbcTemplate.update("DELETE FROM savings_box_lots WHERE box_id = ?", b.getId());
        writeLots(b);
    }

    private void writeLots(SavingsBox b) {
        List<Object[]> rows = new ArrayList<>();
        int position = 0;
        for (SavingsLot lot : b.getLots()) {
            rows.add(new Object[]{lot.getId(), b.getId(), Date.valueOf(lot.getDepositedOn()), position++,
                    lot.getPrincipal(), lot.getBalance(), lot.getPendingYield()});
        }
        if (rows.isEmpty()) return;
        jdbcTemplate.batchUpdate("INSERT INTO savings_box_lots (id, box_id, deposited_on, position, principal, balance, "
                + "pending_yield) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
    }

    @Override
    public Optional<SavingsBox> findById(UUID id) {
        return load("SELECT " + COLUMNS + " FROM savings_boxes WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public List<SavingsBox> findActiveByAccount(UUID accountId) {
        return load("SELECT " + COLUMNS + " FROM savings_boxes WHERE account_id = ? AND status = 'ACTIVE' ORDER BY created_at",
                accountId);
    }

    private List<SavingsBox> load(String sql, Object... args) {
        record Row(UUID id, UUID customerId, UUID accountId, String name, Money goal, Money totalYield,
                   SavingsBox.Status status, LocalDate lastYield, LocalDateTime goalReached, LocalDateTime created,
                   LocalDateTime updated, LocalDateTime closed, long version) {}
        List<Row> rows = jdbcTemplate.query(sql, (rs, n) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("name"), money(rs.getBigDecimal("goal_amount")), Money.of(rs.getBigDecimal("total_yield")),
                SavingsBox.Status.valueOf(rs.getString("status")), rs.getDate("last_yield_date").toLocalDate(),
                toLocal(rs.getTimestamp("goal_reached_at")), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(), toLocal(rs.getTimestamp("closed_at")), rs.getLong("version")),
                args);
        return rows.stream().map(r -> SavingsBox.reconstitute(r.id(), r.customerId(), r.accountId(), r.name(), r.goal(),
                lotsOf(r.id()), r.totalYield(), r.status(), r.lastYield(), r.goalReached(), r.created(), r.updated(),
                r.closed(), r.version())).toList();
    }

    private List<SavingsLot> lotsOf(UUID boxId) {
        return jdbcTemplate.query("SELECT id, deposited_on, principal, balance, pending_yield FROM savings_box_lots "
                        + "WHERE box_id = ? ORDER BY deposited_on, position",
                (rs, n) -> SavingsLot.reconstitute(rs.getObject("id", UUID.class), rs.getDate("deposited_on").toLocalDate(),
                        rs.getBigDecimal("principal"), rs.getBigDecimal("balance"), rs.getBigDecimal("pending_yield")),
                boxId);
    }

    @Override
    public List<UUID> findIdsPendingYieldUntil(LocalDate date) {
        return jdbcTemplate.queryForList("SELECT id FROM savings_boxes WHERE status = 'ACTIVE' AND last_yield_date < ? "
                + "ORDER BY last_yield_date", UUID.class, Date.valueOf(date));
    }

    @Override
    public Optional<LocalDate> oldestYieldDateBefore(LocalDate date) {
        Date oldest = jdbcTemplate.queryForObject("SELECT MIN(last_yield_date) FROM savings_boxes "
                + "WHERE status = 'ACTIVE' AND last_yield_date < ?", Date.class, Date.valueOf(date));
        return Optional.ofNullable(oldest).map(Date::toLocalDate);
    }

    @Override
    public boolean hasActiveBoxes(UUID accountId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM savings_boxes WHERE account_id = ? AND status = 'ACTIVE'", Integer.class, accountId);
        return count != null && count > 0;
    }

    @Override
    public int countActiveByCustomer(UUID customerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM savings_boxes WHERE customer_id = ? AND status = 'ACTIVE'", Integer.class, customerId);
        return count == null ? 0 : count;
    }

    @Override
    public void insertMovement(UUID boxId, MovementType type, Money amount, Money balanceAfter, LocalDateTime at) {
        jdbcTemplate.update("INSERT INTO savings_box_movements (id, box_id, type, amount, balance_after, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), boxId, type.name(), amount.amount(), balanceAfter.amount(), ts(at));
    }

    @Override
    public void insertWithdrawal(UUID boxId, SavingsBox.Withdrawal w, Money balanceAfter, LocalDateTime at) {
        jdbcTemplate.update("INSERT INTO savings_box_movements (id, box_id, type, amount, balance_after, created_at, gain, iof, "
                        + "income_tax, net_amount) VALUES (?, ?, 'WITHDRAWAL', ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), boxId, w.gross().amount(), balanceAfter.amount(), ts(at), w.gain().amount(),
                w.iof().amount(), w.incomeTax().amount(), w.net().amount());
    }

    @Override
    public List<Movement> findMovements(UUID boxId, int limit) {
        return jdbcTemplate.query("SELECT id, type, amount, balance_after, created_at, gain, iof, income_tax, net_amount "
                        + "FROM savings_box_movements WHERE box_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, n) -> movement(rs), boxId, limit);
    }

    private static Movement movement(ResultSet rs) throws SQLException {
        return new Movement(rs.getObject("id", UUID.class), MovementType.valueOf(rs.getString("type")),
                Money.of(rs.getBigDecimal("amount")), Money.of(rs.getBigDecimal("balance_after")),
                rs.getTimestamp("created_at").toLocalDateTime(), money(rs.getBigDecimal("gain")),
                money(rs.getBigDecimal("iof")), money(rs.getBigDecimal("income_tax")), money(rs.getBigDecimal("net_amount")));
    }

    private static Money money(BigDecimal value) {
        return value == null ? null : Money.of(value);
    }

    private static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    private static Timestamp ts(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocal(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
