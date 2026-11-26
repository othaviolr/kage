package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.VirtualCard;
import com.kage.card.domain.enums.VirtualCardKind;
import com.kage.card.domain.enums.VirtualCardStatus;
import com.kage.card.domain.repository.VirtualCardRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcVirtualCardRepository implements VirtualCardRepository {

    private static final String COLUMNS = "id, card_id, nickname, kind, last_digits, number_hash, expiry, status, "
            + "created_at, expires_at, deleted_at";

    private static final RowMapper<VirtualCard> MAPPER = (rs, n) -> new VirtualCard(
            rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class), rs.getString("nickname"),
            VirtualCardKind.valueOf(rs.getString("kind")), rs.getString("last_digits"), rs.getString("number_hash"),
            YearMonth.parse(rs.getString("expiry")), VirtualCardStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toLocalDateTime(), toLocal(rs.getTimestamp("expires_at")),
            toLocal(rs.getTimestamp("deleted_at")));

    private final JdbcTemplate jdbcTemplate;

    public JdbcVirtualCardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(VirtualCard v) {
        return jdbcTemplate.update("INSERT INTO virtual_cards (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (number_hash) DO NOTHING",
                v.id(), v.cardId(), v.nickname(), v.kind().name(), v.lastDigits(), v.numberHash(), v.expiry().toString(),
                v.status().name(), Timestamp.valueOf(v.createdAt()), toTimestamp(v.expiresAt()), toTimestamp(v.deletedAt())) == 1;
    }

    @Override
    public void update(VirtualCard v) {
        jdbcTemplate.update("UPDATE virtual_cards SET status = ?, deleted_at = ? WHERE id = ?",
                v.status().name(), toTimestamp(v.deletedAt()), v.id());
    }

    @Override
    public Optional<VirtualCard> findById(UUID id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM virtual_cards WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<VirtualCard> findByNumberHash(String numberHash) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM virtual_cards WHERE number_hash = ?", MAPPER, numberHash)
                .stream().findFirst();
    }

    @Override
    public List<VirtualCard> findByCardId(UUID cardId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM virtual_cards WHERE card_id = ? ORDER BY created_at DESC, id",
                MAPPER, cardId);
    }

    private static LocalDateTime toLocal(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
