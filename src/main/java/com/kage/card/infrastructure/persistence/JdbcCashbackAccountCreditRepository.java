package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.repository.CashbackAccountCreditRepository;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

public class JdbcCashbackAccountCreditRepository implements CashbackAccountCreditRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCashbackAccountCreditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean insert(Credit c) {
        return jdbcTemplate.update("""
                        INSERT INTO card_cashback_account_credits (source_invoice_id, card_id, amount, credited_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT (source_invoice_id) DO NOTHING
                        """,
                c.sourceInvoiceId(), c.cardId(), c.amount().amount(), Timestamp.valueOf(c.creditedAt())) == 1;
    }

    @Override
    public List<Credit> findByCardId(UUID cardId) {
        return jdbcTemplate.query("""
                        SELECT source_invoice_id, card_id, amount, credited_at FROM card_cashback_account_credits
                        WHERE card_id = ? ORDER BY credited_at
                        """,
                (rs, n) -> new Credit(rs.getObject("source_invoice_id", UUID.class), rs.getObject("card_id", UUID.class),
                        Money.of(rs.getBigDecimal("amount")), rs.getTimestamp("credited_at").toLocalDateTime()),
                cardId);
    }
}
