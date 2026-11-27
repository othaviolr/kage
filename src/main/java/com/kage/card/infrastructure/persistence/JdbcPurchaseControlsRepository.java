package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.repository.PurchaseControlsRepository;
import com.kage.card.domain.valueobject.PurchaseControls;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class JdbcPurchaseControlsRepository implements PurchaseControlsRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPurchaseControlsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PurchaseControls> find(UUID cardId) {
        return jdbcTemplate.query("SELECT online, international, contactless FROM card_purchase_controls WHERE card_id = ?",
                (rs, n) -> new PurchaseControls(rs.getBoolean("online"), rs.getBoolean("international"),
                        rs.getBoolean("contactless")), cardId).stream().findFirst();
    }

    @Override
    public void save(UUID cardId, PurchaseControls c, LocalDateTime updatedAt) {
        jdbcTemplate.update("""
                        INSERT INTO card_purchase_controls (card_id, online, international, contactless, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (card_id) DO UPDATE SET online = EXCLUDED.online,
                            international = EXCLUDED.international, contactless = EXCLUDED.contactless,
                            updated_at = EXCLUDED.updated_at
                        """,
                cardId, c.online(), c.international(), c.contactless(), Timestamp.valueOf(updatedAt));
    }
}
