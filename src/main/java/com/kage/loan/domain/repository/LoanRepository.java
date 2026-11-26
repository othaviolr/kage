package com.kage.loan.domain.repository;

import com.kage.loan.domain.entity.Loan;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    record InstallmentRef(UUID loanId, int number) {}

    /** Parcelas em aberto de empréstimos ativos que vencem em {@code dueDate} e ainda não foram lembradas. */
    List<InstallmentRef> notRemindedDueOn(LocalDate dueDate);

    /** Marca o lembrete. @return false se já tinha sido marcado (outra rodada chegou antes) */
    boolean markReminded(UUID loanId, int number, LocalDateTime at);

    /** Marca que o cliente foi avisado da falha do débito. @return false se já tinha sido avisado */
    boolean markFailureNotified(UUID loanId, int number, LocalDateTime at);
}
