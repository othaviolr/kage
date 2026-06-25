package com.kage.payment.infrastructure.persistence.mapper;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.infrastructure.persistence.entity.PixRefundJpaEntity;
import com.kage.shared.domain.valueobject.Money;

public class PixRefundMapper {

    public static PixRefundJpaEntity toJpa(PixRefund refund) {
        return new PixRefundJpaEntity(
                refund.getRefundId(),
                refund.getOriginalTransactionId(),
                refund.getReason(),
                refund.getRefundAmount().amount(),
                refund.getRefundStatus(),
                refund.getRequestedAt(),
                refund.getProcessedAt(),
                refund.getProcessedBy()
        );
    }

    public static PixRefund toDomain(PixRefundJpaEntity entity) {
        return PixRefund.reconstitute(
                entity.getRefundId(),
                entity.getOriginalTransactionId(),
                entity.getReason(),
                Money.of(entity.getRefundAmount()),
                entity.getRefundStatus(),
                entity.getRequestedAt(),
                entity.getProcessedAt(),
                entity.getProcessedBy()
        );
    }
}