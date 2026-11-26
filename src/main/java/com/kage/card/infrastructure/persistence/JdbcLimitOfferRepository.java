package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.LimitOffer;
import com.kage.card.domain.repository.LimitOfferRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcLimitOfferRepository implements LimitOfferRepository {

    private static final String COLUMNS = "id, card_id, previous_limit, offered_limit, status, created_at, expires_at, responded_at";

    private static final RowMapper<LimitOffer> MAPPER = (rs, n) -> new LimitOffer(
            rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class), Money.of(rs.getBigDecimal("previous_limit")),
            Money.of(rs.getBigDecimal("offered_limit")), LimitOffer.Status.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("expires_at").toLocalDateTime(),
            rs.getTimestamp("responded_at") == null ? null : rs.getTimestamp("responded_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcLimitOfferRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(LimitOffer o) {
        jdbcTemplate.update("INSERT INTO card_limit_offers (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                o.id(), o.cardId(), o.previousLimit().amount(), o.offeredLimit().amount(), o.status().name(),
                Timestamp.valueOf(o.createdAt()), Timestamp.valueOf(o.expiresAt()),
                o.respondedAt() == null ? null : Timestamp.valueOf(o.respondedAt()));
    }

    @Override
    public boolean respond(LimitOffer o) {
        return jdbcTemplate.update("UPDATE card_limit_offers SET status = ?, responded_at = ? WHERE id = ? AND status = 'PENDING'",
                o.status().name(), Timestamp.valueOf(o.respondedAt()), o.id()) == 1;
    }

    @Override
    public Optional<LimitOffer> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_limit_offers WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public List<LimitOffer> findByCardId(UUID cardId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_limit_offers WHERE card_id = ? ORDER BY created_at DESC",
                MAPPER, cardId);
    }
}
