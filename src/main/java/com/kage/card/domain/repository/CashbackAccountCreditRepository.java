package com.kage.card.domain.repository;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Cashback que caiu na conta, um por fatura de origem. */
public interface CashbackAccountCreditRepository {

    record Credit(UUID sourceInvoiceId, UUID cardId, Money amount, LocalDateTime creditedAt) {}

    /** @return false se a fatura de origem já tinha creditado (chave primária) */
    boolean insert(Credit credit);

    List<Credit> findByCardId(UUID cardId);
}
