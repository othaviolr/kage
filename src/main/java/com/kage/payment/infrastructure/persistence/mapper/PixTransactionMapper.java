package com.kage.payment.infrastructure.persistence.mapper;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.infrastructure.persistence.entity.PixTransactionJpaEntity;
import com.kage.shared.domain.valueobject.Money;

public class PixTransactionMapper {

    public static PixTransactionJpaEntity toJpa(PixTransaction transaction) {
        return new PixTransactionJpaEntity(
                transaction.getTransactionId(),
                transaction.getIdempotencyKey(),
                transaction.getSourceAccountId(),
                transaction.getTargetPixKey(),
                transaction.getTargetAccountId(),
                transaction.getAmount().amount(),
                transaction.getDescription(),
                transaction.getTransactionType(),
                transaction.getScheduledDate(),
                transaction.getStatus(),
                transaction.getFailureReason(),
                transaction.getE2eId(),
                transaction.getCreatedAt(),
                transaction.getProcessedAt(),
                transaction.getCompletedAt()
        );
    }

    public static PixTransaction toDomain(PixTransactionJpaEntity entity) {
        return PixTransaction.reconstitute(
                entity.getTransactionId(),
                entity.getIdempotencyKey(),
                entity.getSourceAccountId(),
                entity.getTargetPixKey(),
                entity.getTargetAccountId(),
                Money.of(entity.getAmount()),
                entity.getDescription(),
                entity.getTransactionType(),
                entity.getScheduledDate(),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getE2eId(),
                entity.getCreatedAt(),
                entity.getProcessedAt(),
                entity.getCompletedAt()
        );
    }
}