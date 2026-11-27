package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.card.domain.repository.PurchaseCategoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class JdbcPurchaseCategoryRepository implements PurchaseCategoryRepository {

    private static final RowMapper<Entry> ROW = (rs, n) -> new Entry(rs.getObject("purchase_id", UUID.class),
            rs.getString("mcc"), PurchaseCategory.valueOf(rs.getString("category")), rs.getBoolean("customized"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcPurchaseCategoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(UUID cardId, UUID purchaseId, String mcc, PurchaseCategory category, LocalDateTime at) {
        jdbcTemplate.update("""
                        INSERT INTO card_purchase_categories (card_id, purchase_id, mcc, category, customized, updated_at)
                        VALUES (?, ?, ?, ?, FALSE, ?)
                        ON CONFLICT (card_id, purchase_id) DO NOTHING
                        """,
                cardId, purchaseId, mcc, category.name(), Timestamp.valueOf(at));
    }

    @Override
    public void customize(UUID cardId, UUID purchaseId, PurchaseCategory category, LocalDateTime at) {
        jdbcTemplate.update("""
                        INSERT INTO card_purchase_categories (card_id, purchase_id, mcc, category, customized, updated_at)
                        VALUES (?, ?, NULL, ?, TRUE, ?)
                        ON CONFLICT (card_id, purchase_id)
                        DO UPDATE SET category = EXCLUDED.category, customized = TRUE, updated_at = EXCLUDED.updated_at
                        """,
                cardId, purchaseId, category.name(), Timestamp.valueOf(at));
    }

    @Override
    public Optional<Entry> find(UUID cardId, UUID purchaseId) {
        return jdbcTemplate.query("""
                        SELECT purchase_id, mcc, category, customized FROM card_purchase_categories
                        WHERE card_id = ? AND purchase_id = ?
                        """, ROW, cardId, purchaseId).stream().findFirst();
    }

    @Override
    public Map<UUID, Entry> findByCardId(UUID cardId) {
        return jdbcTemplate.query("""
                        SELECT purchase_id, mcc, category, customized FROM card_purchase_categories WHERE card_id = ?
                        """, ROW, cardId).stream().collect(Collectors.toMap(Entry::purchaseId, e -> e));
    }
}
