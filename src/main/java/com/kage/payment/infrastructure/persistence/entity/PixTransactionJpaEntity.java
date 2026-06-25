package com.kage.payment.infrastructure.persistence.entity;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.enums.TransactionType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pix_transactions")
public class PixTransactionJpaEntity {

    @Id
    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private UUID idempotencyKey;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_pix_key", nullable = false)
    private String targetPixKey;

    @Column(name = "target_account_id")
    private UUID targetAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(name = "scheduled_date")
    private LocalDateTime scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PixTransactionStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "e2e_id", nullable = false, unique = true)
    private String e2eId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public PixTransactionJpaEntity() {}

    public PixTransactionJpaEntity(UUID transactionId, UUID idempotencyKey, UUID sourceAccountId,
                                   String targetPixKey, UUID targetAccountId, BigDecimal amount,
                                   String description, TransactionType transactionType,
                                   LocalDateTime scheduledDate, PixTransactionStatus status,
                                   String failureReason, String e2eId, LocalDateTime createdAt,
                                   LocalDateTime processedAt, LocalDateTime completedAt) {
        this.transactionId = transactionId;
        this.idempotencyKey = idempotencyKey;
        this.sourceAccountId = sourceAccountId;
        this.targetPixKey = targetPixKey;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.description = description;
        this.transactionType = transactionType;
        this.scheduledDate = scheduledDate;
        this.status = status;
        this.failureReason = failureReason;
        this.e2eId = e2eId;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
        this.completedAt = completedAt;
    }

    public UUID getTransactionId() { return transactionId; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public String getTargetPixKey() { return targetPixKey; }
    public UUID getTargetAccountId() { return targetAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public TransactionType getTransactionType() { return transactionType; }
    public LocalDateTime getScheduledDate() { return scheduledDate; }
    public PixTransactionStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getE2eId() { return e2eId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}