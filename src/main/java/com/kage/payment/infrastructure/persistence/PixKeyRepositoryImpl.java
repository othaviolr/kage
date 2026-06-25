package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.infrastructure.persistence.mapper.PixKeyMapper;

import java.util.Optional;
import java.util.UUID;

public class PixKeyRepositoryImpl implements PixKeyRepository {

    private final PixKeyJpaRepository jpaRepository;

    public PixKeyRepositoryImpl(PixKeyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PixKey save(PixKey pixKey) {
        return PixKeyMapper.toDomain(jpaRepository.save(PixKeyMapper.toJpa(pixKey)));
    }

    @Override
    public Optional<PixKey> findById(UUID pixKeyId) {
        return jpaRepository.findById(pixKeyId).map(PixKeyMapper::toDomain);
    }

    @Override
    public Optional<PixKey> findByKeyValue(String keyValue) {
        return jpaRepository.findByKeyValue(keyValue).map(PixKeyMapper::toDomain);
    }

    @Override
    public boolean existsByKeyTypeAndKeyValue(PixKeyType keyType, String keyValue) {
        return jpaRepository.existsByKeyTypeAndKeyValue(keyType, keyValue);
    }
}