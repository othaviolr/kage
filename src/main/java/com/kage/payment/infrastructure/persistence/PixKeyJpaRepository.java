package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.infrastructure.persistence.entity.PixKeyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PixKeyJpaRepository extends JpaRepository<PixKeyJpaEntity, UUID> {
    Optional<PixKeyJpaEntity> findByKeyValue(String keyValue);
    boolean existsByKeyTypeAndKeyValue(PixKeyType keyType, String keyValue);
}