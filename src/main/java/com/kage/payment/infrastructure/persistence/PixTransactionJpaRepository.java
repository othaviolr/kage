package com.kage.payment.infrastructure.persistence;

import com.kage.payment.infrastructure.persistence.entity.PixTransactionJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PixTransactionJpaRepository extends JpaRepository<PixTransactionJpaEntity, UUID> {
    Optional<PixTransactionJpaEntity> findByIdempotencyKey(UUID idempotencyKey);
}