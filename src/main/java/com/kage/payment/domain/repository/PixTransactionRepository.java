package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixTransaction;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixTransactionRepository {
    PixTransaction save(PixTransaction transaction);
    Optional<PixTransaction> findById(UUID transactionId);
    Optional<PixTransaction> findByIdempotencyKey(UUID idempotencyKey);
    /** Soma dos PIX enviados pela conta desde {@code since}, sem contar os que falharam ou foram cancelados. */
    Money sumAmountSentSince(UUID sourceAccountId, LocalDateTime since);

    /** PIX agendados ainda pendentes com data até {@code date} (inclusive), mais antigos primeiro. */
    List<PixTransaction> findScheduledDueUntil(LocalDate date);

    List<PixTransaction> findPendingScheduledByAccount(UUID accountId);

    /** Ocorrências já criadas de um PIX recorrente, da primeira à última. */
    List<PixTransaction> findByRecurrence(UUID recurrenceId);
}