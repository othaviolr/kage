package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

public class GetPixRefund {

    private final PixRefundRepository pixRefundRepository;

    public GetPixRefund(PixRefundRepository pixRefundRepository) {
        this.pixRefundRepository = pixRefundRepository;
    }

    public Output execute(UUID refundId) {
        PixRefund refund = pixRefundRepository.findById(refundId)
                .orElseThrow(() -> new NotFoundException("Estorno não encontrado"));

        return new Output(refund.getRefundId(), refund.getOriginalTransactionId(), refund.getReason(),
                refund.getRefundAmount().amount(), refund.getRefundStatus().name(), refund.getFailureReason(),
                refund.getProcessedBy(), refund.getRequestedAt().toString(),
                refund.getProcessedAt() != null ? refund.getProcessedAt().toString() : null);
    }

    public record Output(UUID refundId, UUID originalTransactionId, String reason, BigDecimal refundAmount,
                         String refundStatus, String failureReason, String processedBy, String requestedAt,
                         String processedAt) {}
}
