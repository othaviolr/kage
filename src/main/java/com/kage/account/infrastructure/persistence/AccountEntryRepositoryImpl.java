package com.kage.account.infrastructure.persistence;

import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lançamentos são append-only (nunca atualizados), então JdbcTemplate basta: não há estado de
 * entidade para o JPA gerenciar.
 */
public class AccountEntryRepositoryImpl implements AccountEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountEntryRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(AccountEntry entry) {
        jdbcTemplate.update("""
                        INSERT INTO account_entries (id, account_id, type, amount, balance_after, reference_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                entry.id(), entry.accountId(), entry.type().name(), entry.amount().amount(),
                entry.balanceAfter().amount(), entry.referenceId(), Timestamp.valueOf(entry.createdAt()));
    }

    @Override
    public Money sumByTypeSince(UUID accountId, EntryType type, LocalDateTime since) {
        BigDecimal sum = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0) FROM account_entries
                        WHERE account_id = ? AND type = ? AND created_at >= ?
                        """,
                BigDecimal.class, accountId, type.name(), Timestamp.valueOf(since));
        return Money.of(sum);
    }

    @Override
    public List<AccountEntry> findByAccountIdBetween(UUID accountId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                        SELECT id, account_id, type, amount, balance_after, reference_id, created_at
                        FROM account_entries
                        WHERE account_id = ? AND created_at >= ? AND created_at < ?
                        ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> new AccountEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        EntryType.valueOf(rs.getString("type")),
                        Money.of(rs.getBigDecimal("amount")),
                        Money.of(rs.getBigDecimal("balance_after")),
                        rs.getObject("reference_id", UUID.class),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                accountId, Timestamp.valueOf(from), Timestamp.valueOf(to));
    }
}
