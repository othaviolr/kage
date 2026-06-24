package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.enums.PixKeyType;

import java.util.Optional;
import java.util.UUID;

public interface PixKeyRepository {
    PixKey save(PixKey pixKey);
    Optional<PixKey> findById(UUID pixKeyId);
    Optional<PixKey> findByKeyValue(String keyValue);
    boolean existsByKeyTypeAndKeyValue(PixKeyType keyType, String keyValue);
}