package com.kage.payment.infrastructure.persistence;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.infrastructure.persistence.mapper.PixTransactionMapper;

import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        // PROCESSING conta: o dinheiro ainda está a caminho, mas o limite já foi consumido.
        // PENDING (agendado ainda não executado), FAILED e CANCELLED não consomem limite.
        BigDecimal sum = jpaRepository.sumAmountSentSince(sourceAccountId, since,
                List.of(PixTransactionStatus.PROCESSING, PixTransactionStatus.COMPLETED));
        return sum == null ? Money.ZERO : Money.of(sum); // SUM sem linhas devolve null
    }

    @Override
    public List<PixTransaction> findScheduledDueUntil(LocalDate date) {
        return jpaRepository.findScheduledDueBefore(date.plusDays(1).atStartOfDay()).stream()
                .map(PixTransactionMapper::toDomain).toList();
    }

    @Override
    public List<PixTransaction> findPendingScheduledByAccount(UUID accountId) {
        return jpaRepository.findPendingScheduledByAccount(accountId).stream().map(PixTransactionMapper::toDomain).toList();
    }

    @Override
    public List<PixTransaction> findByRecurrence(UUID recurrenceId) {
        return jpaRepository.findByRecurrenceIdOrderByOccurrenceNumber(recurrenceId).stream()
                .map(PixTransactionMapper::toDomain).toList();
    }
}