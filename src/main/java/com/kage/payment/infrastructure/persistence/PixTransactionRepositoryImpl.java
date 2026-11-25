package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.mapper.PixTransactionMapper;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PixTransactionRepositoryImpl implements PixTransactionRepository {

    private final PixTransactionJpaRepository jpaRepository;

    public PixTransactionRepositoryImpl(PixTransactionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PixTransaction save(PixTransaction transaction) {
        return PixTransactionMapper.toDomain(jpaRepository.save(PixTransactionMapper.toJpa(transaction)));
    }

    @Override
    public Optional<PixTransaction> findById(UUID transactionId) {
        return jpaRepository.findById(transactionId).map(PixTransactionMapper::toDomain);
    }

    @Override
    public Optional<PixTransaction> findByIdempotencyKey(UUID idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(PixTransactionMapper::toDomain);
    }

    @Override
    public Money sumAmountSentSince(UUID sourceAccountId, LocalDateTime since) {
        // PENDING/PROCESSING contam: o dinheiro ainda não saiu, mas o limite já foi consumido
        BigDecimal sum = jpaRepository.sumAmountSentSince(sourceAccountId, since,
                List.of(PixTransactionStatus.FAILED, PixTransactionStatus.CANCELLED));
        return sum == null ? Money.ZERO : Money.of(sum); // SUM sem linhas devolve null
    }
}