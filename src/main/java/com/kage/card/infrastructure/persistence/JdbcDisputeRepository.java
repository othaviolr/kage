package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.enums.DisputeReason;
import com.kage.card.domain.enums.DisputeStatus;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcDisputeRepository implements DisputeRepository {

    private static final String COLUMNS = "id, card_id, purchase_id, description, amount, credited_amount, credit_month, "
            + "reason, details, status, card_blocked, opened_at, resolved_at, resolution_note, reversal_month, "
            + "virtual_card_id, virtual_card_deleted, interest_refunded";

    private static final RowMapper<Dispute> MAPPER = (rs, n) -> {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new Dispute(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getObject("purchase_id", UUID.class), rs.getString("description"),
                Money.of(rs.getBigDecimal("amount")), Money.of(rs.getBigDecimal("credited_amount")),
                rs.getString("credit_month"), DisputeReason.valueOf(rs.getString("reason")), rs.getString("details"),
                DisputeStatus.valueOf(rs.getString("status")), rs.getBoolean("card_blocked"),
                rs.getTimestamp("opened_at").toLocalDateTime(), resolvedAt == null ? null : resolvedAt.toLocalDateTime(),
                rs.getString("resolution_note"), rs.getString("reversal_month"),
                rs.getObject("virtual_card_id", UUID.class), rs.getBoolean("virtual_card_deleted"),
                Money.of(rs.getBigDecimal("interest_refunded")));
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcDisputeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(Dispute d) {
        jdbcTemplate.update("INSERT INTO card_disputes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.id(), d.cardId(), d.purchaseId(), d.description(), d.amount().amount(), d.creditedAmount().amount(),
                d.creditMonth(), d.reason().name(), d.details(), d.status().name(), d.cardBlocked(),
                Timestamp.valueOf(d.openedAt()), d.resolvedAt() == null ? null : Timestamp.valueOf(d.resolvedAt()),
                d.resolutionNote(), d.reversalMonth(), d.virtualCardId(), d.virtualCardDeleted(),
                d.interestRefunded().amount());
    }

    @Override
    public boolean resolve(Dispute d) {
        return jdbcTemplate.update("UPDATE card_disputes SET status = ?, resolved_at = ?, resolution_note = ?, "
                        + "reversal_month = ?, interest_refunded = ? WHERE id = ? AND status = 'OPEN'",
                d.status().name(), Timestamp.valueOf(d.resolvedAt()), d.resolutionNote(), d.reversalMonth(),
                d.interestRefunded().amount(), d.id()) == 1;
    }

    @Override
    public Optional<Dispute> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_disputes WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Dispute> findByCardIdAndPurchaseId(UUID cardId, UUID purchaseId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_disputes WHERE card_id = ? AND purchase_id = ?",
                MAPPER, cardId, purchaseId).stream().findFirst();
    }

    @Override
    public List<Dispute> findByCardId(UUID cardId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_disputes WHERE card_id = ? ORDER BY opened_at DESC",
                MAPPER, cardId);
    }
}
