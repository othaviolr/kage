package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.CardPlastic;
import com.kage.card.domain.enums.PlasticReason;
import com.kage.card.domain.enums.PlasticStatus;
import com.kage.card.domain.repository.CardPlasticRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public class JdbcCardPlasticRepository implements CardPlasticRepository {

    private static final String COLUMNS = "id, card_id, sequence, last_digits, number_hash, expiry, status, issue_reason, "
            + "issued_at, activated_at, cancelled_at, cancel_reason";

    private static final RowMapper<CardPlastic> MAPPER = (rs, n) -> {
        String cancelReason = rs.getString("cancel_reason");
        return new CardPlastic(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class), rs.getInt("sequence"),
                rs.getString("last_digits"), rs.getString("number_hash"), YearMonth.parse(rs.getString("expiry")),
                PlasticStatus.valueOf(rs.getString("status")), PlasticReason.valueOf(rs.getString("issue_reason")),
                rs.getTimestamp("issued_at").toLocalDateTime(), toLocal(rs.getTimestamp("activated_at")),
                toLocal(rs.getTimestamp("cancelled_at")), cancelReason == null ? null : PlasticReason.valueOf(cancelReason));
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcCardPlasticRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(CardPlastic p) {
        // só a colisão de número é tratada aqui; as outras violações (duas vias ativas) são erro de verdade
        return jdbcTemplate.update("INSERT INTO card_plastics (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (number_hash) DO NOTHING",
                p.id(), p.cardId(), p.sequence(), p.lastDigits(), p.numberHash(), p.expiry().toString(), p.status().name(),
                p.issueReason().name(), Timestamp.valueOf(p.issuedAt()), toTimestamp(p.activatedAt()),
                toTimestamp(p.cancelledAt()), p.cancelReason() == null ? null : p.cancelReason().name()) == 1;
    }

    @Override
    public void update(CardPlastic p) {
        jdbcTemplate.update("UPDATE card_plastics SET status = ?, activated_at = ?, cancelled_at = ?, cancel_reason = ? "
                        + "WHERE id = ?",
                p.status().name(), toTimestamp(p.activatedAt()), toTimestamp(p.cancelledAt()),
                p.cancelReason() == null ? null : p.cancelReason().name(), p.id());
    }

    @Override
    public List<CardPlastic> findByCardId(UUID cardId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM card_plastics WHERE card_id = ? ORDER BY sequence DESC",
                MAPPER, cardId);
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
