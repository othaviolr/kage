package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.infrastructure.persistence.mapper.PixRefundMapper;

import java.util.Optional;
import java.util.UUID;

public class PixRefundRepositoryImpl implements PixRefundRepository {

    private final PixRefundJpaRepository jpaRepository;

    public PixRefundRepositoryImpl(PixRefundJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PixRefund save(PixRefund refund) {
        return PixRefundMapper.toDomain(jpaRepository.save(PixRefundMapper.toJpa(refund)));
    }

    @Override
    public Optional<PixRefund> findById(UUID refundId) {
        return jpaRepository.findById(refundId).map(PixRefundMapper::toDomain);
    }

    @Override
    public Optional<PixRefund> findByOriginalTransactionId(UUID originalTransactionId) {
        return jpaRepository.findByOriginalTransactionId(originalTransactionId).map(PixRefundMapper::toDomain);
    }
}