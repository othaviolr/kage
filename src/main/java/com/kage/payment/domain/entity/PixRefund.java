package com.kage.payment.domain.entity;

import com.kage.payment.domain.enums.RefundStatus;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

public class PixRefund {

    private final UUID refundId;
    private final UUID originalTransactionId;
    private final String reason;
    private final Money refundAmount;
    private RefundStatus refundStatus;
    private final LocalDateTime requestedAt;
    private LocalDateTime processedAt;
    private String processedBy;

    private PixRefund(UUID refundId, UUID originalTransactionId, String reason,
                      Money refundAmount, RefundStatus refundStatus, LocalDateTime requestedAt, LocalDateTime processedAt, String processedBy) {
        this.refundId = refundId;
        this.originalTransactionId = originalTransactionId;
        this.reason = reason;
        this.refundAmount = refundAmount;
        this.refundStatus = refundStatus;
        this.requestedAt = requestedAt;
        this.processedAt = processedAt;
        this.processedBy = processedBy;
    }

    public static PixRefund create(UUID originalTransactionId, String reason, Money refundAmount) {
        if (reason == null || reason.isBlank()) {
            throw new DomainException("Motivo do estorno é obrigatório");
        }
        return new PixRefund(UUID.randomUUID(), originalTransactionId, reason, refundAmount, RefundStatus.REQUESTED, LocalDateTime.now(),null,null);
    }

    public static PixRefund reconstitute(UUID refundId, UUID originalTransactionId, String reason,
                                         Money refundAmount, RefundStatus refundStatus, LocalDateTime requestedAt, LocalDateTime processedAt, String processedBy) {
        return new PixRefund(refundId, originalTransactionId, reason, refundAmount,
                refundStatus, requestedAt, processedAt, processedBy);
    }

    public void approve(String processedBy) {
        if (this.refundStatus != RefundStatus.REQUESTED) {
            throw new DomainException("Estorno não está aguardando aprovação");
        }
        this.refundStatus = RefundStatus.APPROVED;
        this.processedBy = processedBy;
        this.processedAt = LocalDateTime.now();
    }

    public void reject(String processedBy) {
        if (this.refundStatus != RefundStatus.REQUESTED) {
            throw new DomainException("Estorno não está aguardando aprovação");
        }
        this.refundStatus = RefundStatus.REJECTED;
        this.processedBy = processedBy;
        this.processedAt = LocalDateTime.now();
    }

    public void complete() {
        if (this.refundStatus != RefundStatus.APPROVED) {
            throw new DomainException("Estorno precisa estar aprovado para ser concluído");
        }
        this.refundStatus = RefundStatus.COMPLETED;
        this.processedAt = LocalDateTime.now();
    }

    // getters
    public UUID getRefundId() { return refundId; }
    public UUID getOriginalTransactionId() { return originalTransactionId; }
    public String getReason() { return reason; }
    public Money getRefundAmount() { return refundAmount; }
    public RefundStatus getRefundStatus() { return refundStatus; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public String getProcessedBy() { return processedBy; }
}