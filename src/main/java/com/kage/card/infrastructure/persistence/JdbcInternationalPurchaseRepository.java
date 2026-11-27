package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.repository.InternationalPurchaseRepository;
import com.kage.card.domain.service.InternationalPurchasePolicy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public class JdbcInternationalPurchaseRepository implements InternationalPurchaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcInternationalPurchaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(UUID cardId, UUID purchaseId, InternationalPurchasePolicy.Quote q, LocalDateTime purchasedAt) {
        jdbcTemplate.update("""
                        INSERT INTO card_international_purchases (purchase_id, card_id, currency, foreign_amount, ptax,
                                                                  ptax_date, spread, rate, brl_amount, iof, purchased_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                purchaseId, cardId, q.currency(), q.foreignAmount(), q.ptax(), Date.valueOf(q.ptaxDate()), q.spread(),
                q.rate(), q.brl(), q.iof(), Timestamp.valueOf(purchasedAt));
    }

    @Override
    public Optional<InternationalPurchasePolicy.Quote> find(UUID purchaseId) {
        return jdbcTemplate.query("""
                        SELECT currency, foreign_amount, ptax, ptax_date, spread, rate, brl_amount, iof
                        FROM card_international_purchases WHERE purchase_id = ?
                        """,
                (rs, n) -> new InternationalPurchasePolicy.Quote(rs.getString("currency"), rs.getBigDecimal("foreign_amount"),
                        rs.getBigDecimal("ptax"), rs.getDate("ptax_date").toLocalDate(), rs.getBigDecimal("spread"),
                        rs.getBigDecimal("rate"), rs.getBigDecimal("brl_amount"), rs.getBigDecimal("iof"),
                        rs.getBigDecimal("brl_amount").add(rs.getBigDecimal("iof"))),
                purchaseId).stream().findFirst();
    }
}
