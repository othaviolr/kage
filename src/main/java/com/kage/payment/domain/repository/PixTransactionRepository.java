package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixTransaction;

import java.util.Optional;
import java.util.UUID;

public interface PixTransactionRepository {
    PixTransaction save(PixTransaction transaction);
    Optional<PixTransaction> findById(UUID transactionId);
    Optional<PixTransaction> findByIdempotencyKey(UUID idempotencyKey);
}