package com.kage.savings.infrastructure.persistence;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC com locking otimista manual: UPDATE ... WHERE version = ? que não acha a linha = alguém mudou antes. */
public class SavingsBoxRepositoryImpl implements SavingsBoxRepository {

    private static final String COLUMNS = "id, customer_id, account_id, name, goal_amount, balance, pending_yield, total_yield, "
            + "status, last_yield_date, goal_reached_at, created_at, updated_at, closed_at, version";

    private static final RowMapper<SavingsBox> MAPPER = (rs, rowNum) -> SavingsBox.reconstitute(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("name"),
            rs.getBigDecimal("goal_amount") == null ? null : Money.of(rs.getBigDecimal("goal_amount")),
            Money.of(rs.getBigDecimal("balance")),
            rs.getBigDecimal("pending_yield"),
            Money.of(rs.getBigDecimal("total_yield")),
            SavingsBox.Status.valueOf(rs.getString("status")),
            rs.getDate("last_yield_date").toLocalDate(),
            toLocal(rs.getTimestamp("goal_reached_at")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            toLocal(rs.getTimestamp("closed_at")),
            rs.getLong("version"));

    private final JdbcTemplate jdbcTemplate;

    public SavingsBoxRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(SavingsBox b) {
        jdbcTemplate.update("INSERT INTO savings_boxes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                b.getId(), b.getCustomerId(), b.getAccountId(), b.getName(), amount(b.getGoalAmount()),
                b.getBalance().amount(), b.getPendingYield(), b.getTotalYield().amount(), b.getStatus().name(),
                Date.valueOf(b.getLastYieldDate()), ts(b.getGoalReachedAt()), ts(b.getCreatedAt()), ts(b.getUpdatedAt()),
                ts(b.getClosedAt()));
    }

    @Override
    public void update(SavingsBox b) {
        int updated = jdbcTemplate.update("""
                        UPDATE savings_boxes SET name = ?, balance = ?, pending_yield = ?, total_yield = ?, status = ?,
                               last_yield_date = ?, goal_reached_at = ?, updated_at = ?, closed_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                b.getName(), b.getBalance().amount(), b.getPendingYield(), b.getTotalYield().amount(), b.getStatus().name(),
                Date.valueOf(b.getLastYieldDate()), ts(b.getGoalReachedAt()), ts(b.getUpdatedAt()), ts(b.getClosedAt()),
                b.getId(), b.getVersion());
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Caixinha " + b.getId() + " foi alterada por outra operação");
        }
    }

    @Override
    public Optional<SavingsBox> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM savings_boxes WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<SavingsBox> findActiveByAccount(UUID accountId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM savings_boxes WHERE account_id = ? AND status = 'ACTIVE' "
                + "ORDER BY created_at", MAPPER, accountId);
    }

    @Override
    public List<UUID> findIdsPendingYieldUntil(LocalDate date) {
        return jdbcTemplate.queryForList("SELECT id FROM savings_boxes WHERE status = 'ACTIVE' AND last_yield_date < ? "
                + "ORDER BY last_yield_date", UUID.class, Date.valueOf(date));
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
    public List<Movement> findMovements(UUID boxId, int limit) {
        return jdbcTemplate.query("SELECT id, type, amount, balance_after, created_at FROM savings_box_movements "
                        + "WHERE box_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new Movement(rs.getObject("id", UUID.class), MovementType.valueOf(rs.getString("type")),
                        Money.of(rs.getBigDecimal("amount")), Money.of(rs.getBigDecimal("balance_after")),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                boxId, limit);
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
