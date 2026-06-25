package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.shared.domain.exception.DomainException;

import java.util.UUID;

public class RegisterPixKey {

    private final PixKeyRepository pixKeyRepository;

    public RegisterPixKey(PixKeyRepository pixKeyRepository) {
        this.pixKeyRepository = pixKeyRepository;
    }

    public Output execute(Input input) {
        if (pixKeyRepository.existsByKeyTypeAndKeyValue(input.keyType(), input.keyValue())) {
            throw new DomainException("Chave PIX já cadastrada no sistema");
        }

        PixKey pixKey = PixKey.create(input.accountId(), input.keyType(), input.keyValue());
        PixKey saved = pixKeyRepository.save(pixKey);

        return new Output(saved.getPixKeyId(), saved.getAccountId(), saved.getKeyType(), saved.getKeyValue(), saved.getKeyStatus().name(), saved.getCreatedAt().toString());
    }

    public record Input(UUID accountId, PixKeyType keyType, String keyValue
    ) {}

    public record Output(UUID pixKeyId, UUID accountId, PixKeyType keyType, String keyValue, String keyStatus, String createdAt
    ) {}
}