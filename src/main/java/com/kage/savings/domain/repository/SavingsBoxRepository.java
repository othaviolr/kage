package com.kage.savings.domain.repository;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavingsBoxRepository {

    void insert(SavingsBox box);

    /** Locking otimista: a versão lida tem que ser a do banco, senão OptimisticLockingFailureException (409). */
    void update(SavingsBox box);

    Optional<SavingsBox> findById(UUID id);

    List<SavingsBox> findActiveByAccount(UUID accountId);

    /** Ids das caixinhas ativas que ainda não renderam até {@code date}. */
    List<UUID> findIdsPendingYieldUntil(LocalDate date);

    boolean hasActiveBoxes(UUID accountId);

    int countActiveByCustomer(UUID customerId);

    void insertMovement(UUID boxId, MovementType type, Money amount, Money balanceAfter, LocalDateTime at);

    List<Movement> findMovements(UUID boxId, int limit);

    enum MovementType { DEPOSIT, WITHDRAWAL, YIELD }

    record Movement(UUID id, MovementType type, Money amount, Money balanceAfter, LocalDateTime createdAt) {}
}
