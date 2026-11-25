package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixRecurrence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixRecurrenceRepository {
    void insert(PixRecurrence recurrence);

    /** Locking otimista: lança OptimisticLockingFailureException se outra operação mudou a recorrência antes. */
    void update(PixRecurrence recurrence);

    Optional<PixRecurrence> findById(UUID id);

    /** Recorrências ativas da conta, mais recentes primeiro. */
    List<PixRecurrence> findActiveByAccount(UUID accountId);
}
