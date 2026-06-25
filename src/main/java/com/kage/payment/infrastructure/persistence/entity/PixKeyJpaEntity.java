package com.kage.payment.infrastructure.persistence.entity;

import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.PixKeyType;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "pix_keys")
public class PixKeyJpaEntity {

    @Id
    @Column(name = "pix_key_id")
    private UUID pixKeyId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_type", nullable = false)
    private PixKeyType keyType;

    @Column(name = "key_value", nullable = false, unique = true)
    private String keyValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_status", nullable = false)
    private PixKeyStatus keyStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public PixKeyJpaEntity() {}

    public PixKeyJpaEntity(UUID pixKeyId, UUID accountId, PixKeyType keyType, String keyValue,
                           PixKeyStatus keyStatus, LocalDateTime createdAt, LocalDateTime deletedAt) {
        this.pixKeyId = pixKeyId;
        this.accountId = accountId;
        this.keyType = keyType;
        this.keyValue = keyValue;
        this.keyStatus = keyStatus;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    public UUID getPixKeyId() { return pixKeyId; }
    public UUID getAccountId() { return accountId; }
    public PixKeyType getKeyType() { return keyType; }
    public String getKeyValue() { return keyValue; }
    public PixKeyStatus getKeyStatus() { return keyStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}