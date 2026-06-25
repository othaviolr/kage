package com.kage.payment.infrastructure.persistence;

import com.kage.payment.infrastructure.persistence.entity.PixRefundJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PixRefundJpaRepository extends JpaRepository<PixRefundJpaEntity, UUID> {
    Optional<PixRefundJpaEntity> findByOriginalTransactionId(UUID originalTransactionId);
}