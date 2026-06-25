package com.kage.payment.infrastructure.persistence.entity;

import com.kage.payment.domain.enums.RefundStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pix_refunds")
public class PixRefundJpaEntity {

    @Id
    @Column(name = "refund_id")
    private UUID refundId;

    @Column(name = "original_transaction_id", nullable = false)
    private UUID originalTransactionId;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false)
    private RefundStatus refundStatus;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processed_by")
    private String processedBy;

    public PixRefundJpaEntity() {}

    public PixRefundJpaEntity(UUID refundId, UUID originalTransactionId, String reason,
                              BigDecimal refundAmount, RefundStatus refundStatus,
                              LocalDateTime requestedAt, LocalDateTime processedAt,
                              String processedBy) {
        this.refundId = refundId;
        this.originalTransactionId = originalTransactionId;
        this.reason = reason;
        this.refundAmount = refundAmount;
        this.refundStatus = refundStatus;
        this.requestedAt = requestedAt;
        this.processedAt = processedAt;
        this.processedBy = processedBy;
    }

    public UUID getRefundId() { return refundId; }
    public UUID getOriginalTransactionId() { return originalTransactionId; }
    public String getReason() { return reason; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public RefundStatus getRefundStatus() { return refundStatus; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public String getProcessedBy() { return processedBy; }
}