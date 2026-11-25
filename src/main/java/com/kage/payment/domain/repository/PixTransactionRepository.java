package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixTransaction;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PixTransactionRepository {
    PixTransaction save(PixTransaction transaction);
    Optional<PixTransaction> findById(UUID transactionId);
    Optional<PixTransaction> findByIdempotencyKey(UUID idempotencyKey);
    /** Soma dos PIX enviados pela conta desde {@code since}, sem contar os que falharam ou foram cancelados. */
    Money sumAmountSentSince(UUID sourceAccountId, LocalDateTime since);
}