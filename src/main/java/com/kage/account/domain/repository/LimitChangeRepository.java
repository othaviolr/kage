package com.kage.account.domain.repository;

import com.kage.account.domain.enums.LimitType;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Pedidos de aumento de limite (valem 24h depois do pedido). */
public interface LimitChangeRepository {

    enum Status { PENDING, APPLIED, CANCELLED, REPLACED }

    record LimitChangeRequest(UUID id, UUID accountId, LimitType type, Money previousAmount, Money requestedAmount,
                              LocalDateTime requestedAt, LocalDateTime effectiveAt, Status status) {}

    void insert(LimitChangeRequest request);

    List<LimitChangeRequest> findPending(UUID accountId);

    /** Pendentes da conta que já chegaram na hora, do mais antigo para o mais novo. */
    List<LimitChangeRequest> findDue(UUID accountId, LocalDateTime now);

    /** Contas com algum pedido vencido (para o job). */
    List<UUID> accountsWithDue(LocalDateTime now, int limit);

    void markApplied(UUID requestId, LocalDateTime now);

    /** Um novo pedido (ou uma redução) do mesmo limite substitui o pendente. */
    void replacePending(UUID accountId, LimitType type, LocalDateTime now);

    /** @return false se não existe, não é da conta ou não está mais pendente */
    boolean cancel(UUID requestId, UUID accountId, LocalDateTime now);
}
