package com.kage.payment.domain.repository;

import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticCharge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixAutomaticRepository {
    void insert(PixAutomaticAuthorization authorization);

    /** Locking otimista: lança OptimisticLockingFailureException se outra operação mudou antes. */
    void update(PixAutomaticAuthorization authorization);

    Optional<PixAutomaticAuthorization> findById(UUID id);

    /** Autorizações em que a conta é pagadora ou recebedora, mais recentes primeiro. */
    List<PixAutomaticAuthorization> findByAccount(UUID accountId);

    /** Lança DuplicateKeyException se o ciclo já tem cobrança. */
    void insertCharge(PixAutomaticCharge charge);

    Optional<PixAutomaticCharge> findChargeById(UUID id);

    List<PixAutomaticCharge> findCharges(UUID authorizationId);
}
