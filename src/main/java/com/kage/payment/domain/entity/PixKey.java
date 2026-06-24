package com.kage.payment.domain.entity;

import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.PixKeyType;

import java.time.LocalDateTime;
import java.util.UUID;

public class PixKey {

    private final UUID pixKeyId;
    private final UUID accountId;
    private final PixKeyType keyType;
    private final String keyValue;
    private PixKeyStatus keyStatus;
    private final LocalDateTime createdAt;
    private LocalDateTime deletedAt;

    private PixKey(UUID pixKeyId, UUID accountId, PixKeyType keyType, String keyValue, PixKeyStatus keyStatus, LocalDateTime createdAt, LocalDateTime deletedAt) {
        this.pixKeyId = pixKeyId;
        this.accountId = accountId;
        this.keyType = keyType;
        this.keyValue = keyValue;
        this.keyStatus = keyStatus;
        this.createdAt = createdAt;
        this.deletedAt = deletedAt;
    }

    public static PixKey create(UUID accountId, PixKeyType keyType, String keyValue) {
        return new PixKey(UUID.randomUUID(), accountId, keyType, keyValue, PixKeyStatus.ACTIVE, LocalDateTime.now(),null);
    }

    public static PixKey reconstitute(UUID pixKeyId, UUID accountId, PixKeyType keyType, String keyValue, PixKeyStatus keyStatus, LocalDateTime createdAt, LocalDateTime deletedAt) {
        return new PixKey(pixKeyId, accountId, keyType, keyValue, keyStatus, createdAt, deletedAt);
    }

    public void delete() {
        if (this.keyStatus != PixKeyStatus.ACTIVE) {
            throw new com.kage.shared.domain.exception.DomainException("Chave PIX não está ativa");
        }
        this.keyStatus = PixKeyStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }

    // getters
    public UUID getPixKeyId() { return pixKeyId; }
    public UUID getAccountId() { return accountId; }
    public PixKeyType getKeyType() { return keyType; }
    public String getKeyValue() { return keyValue; }
    public PixKeyStatus getKeyStatus() { return keyStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}