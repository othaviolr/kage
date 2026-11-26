package com.kage.loan.domain.repository;

import com.kage.loan.domain.entity.Loan;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRepository {

    /** UNIQUE: um empréstimo ACTIVE por conta. */
    void insert(Loan loan);

    /**
     * Grava status e parcelas, com trava otimista.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException se outro processo mudou antes
     */
    void update(Loan loan);

    Optional<Loan> findById(UUID id);

    /** Da conta, do mais novo para o mais antigo. */
    List<Loan> findByAccountId(UUID accountId);

    Optional<Loan> findActiveByAccountId(UUID accountId);

    /** Empréstimos ativos com parcela vencida até {@code date} ainda em aberto (para o débito automático). */
    List<UUID> activeWithDueUntil(LocalDate date);
}
