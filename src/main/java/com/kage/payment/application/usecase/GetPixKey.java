package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.shared.domain.exception.DomainException;

import java.util.UUID;

public class GetPixKey {

    private final PixKeyRepository pixKeyRepository;

    public GetPixKey(PixKeyRepository pixKeyRepository) {
        this.pixKeyRepository = pixKeyRepository;
    }

    public Output execute(UUID pixKeyId) {
        PixKey pixKey = pixKeyRepository.findById(pixKeyId).orElseThrow(() -> new DomainException("Chave PIX não encontrada"));

        return new Output(
                pixKey.getPixKeyId(),
                pixKey.getAccountId(),
                pixKey.getKeyType(),
                pixKey.getKeyValue(),
                pixKey.getKeyStatus().name(),
                pixKey.getCreatedAt().toString(),
                pixKey.getDeletedAt() != null ? pixKey.getDeletedAt().toString() : null);
    }

    public record Output(UUID pixKeyId, UUID accountId, com.kage.payment.domain.enums.PixKeyType keyType, String keyValue, String keyStatus, String createdAt, String deletedAt) {}
}