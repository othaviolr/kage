package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.card.domain.repository.CategoryBudgetRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcCategoryBudgetRepository implements CategoryBudgetRepository {

    private static final RowMapper<Budget> ROW = (rs, n) -> new Budget(PurchaseCategory.valueOf(rs.getString("category")),
            Money.of(rs.getBigDecimal("amount")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcCategoryBudgetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<Budget> findByCardId(UUID cardId) {
        return jdbcTemplate.query("SELECT category, amount FROM card_category_budgets WHERE card_id = ?", ROW, cardId);
    }

    @Override
    public Optional<Budget> find(UUID cardId, PurchaseCategory category) {
        return jdbcTemplate.query("SELECT category, amount FROM card_category_budgets WHERE card_id = ? AND category = ?",
                ROW, cardId, category.name()).stream().findFirst();
    }

    @Override
    public void save(UUID cardId, PurchaseCategory category, Money amount, LocalDateTime at) {
        jdbcTemplate.update("""
                        INSERT INTO card_category_budgets (card_id, category, amount, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (card_id, category) DO UPDATE SET amount = EXCLUDED.amount, updated_at = EXCLUDED.updated_at
                        """,
                cardId, category.name(), amount.amount(), Timestamp.valueOf(at));
        clearAlerts(cardId, category);
    }

    @Override
    public boolean delete(UUID cardId, PurchaseCategory category) {
        clearAlerts(cardId, category);
        return jdbcTemplate.update("DELETE FROM card_category_budgets WHERE card_id = ? AND category = ?",
                cardId, category.name()) > 0;
    }

    @Override
    public boolean markAlerted(UUID cardId, PurchaseCategory category, YearMonth referenceMonth, int threshold,
                               LocalDateTime alertedAt) {
        return jdbcTemplate.update("""
                        INSERT INTO card_budget_alerts (card_id, category, reference_month, threshold, alerted_at)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """,
                cardId, category.name(), referenceMonth.toString(), threshold, Timestamp.valueOf(alertedAt)) > 0;
    }

    private void clearAlerts(UUID cardId, PurchaseCategory category) {
        jdbcTemplate.update("DELETE FROM card_budget_alerts WHERE card_id = ? AND category = ?", cardId, category.name());
    }
}
