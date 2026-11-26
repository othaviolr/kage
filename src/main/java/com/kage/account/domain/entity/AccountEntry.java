package com.kage.account.domain.entity;

import com.kage.shared.domain.DomainTime;
import com.kage.account.domain.enums.EntryType;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lançamento imutável do extrato: toda mudança de saldo gera um. É a base do extrato e dos limites
 * acumulados (quanto já foi sacado hoje, por exemplo). Guarda o saldo resultante para o extrato não
 * precisar recalcular o histórico.
 *
 * @param referenceId id da operação de origem (transação PIX, estorno); nulo para depósito/saque
 */
public record AccountEntry(UUID id, UUID accountId, EntryType type, Money amount, Money balanceAfter,
                           UUID referenceId, LocalDateTime createdAt) {

    public AccountEntry {
        if (id == null) throw new ValidationException("Identificador do lançamento é obrigatório");
        if (accountId == null) throw new ValidationException("Conta do lançamento é obrigatória");
        if (type == null) throw new ValidationException("Tipo do lançamento é obrigatório");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor do lançamento deve ser maior que zero");
        if (balanceAfter == null) throw new ValidationException("Saldo após o lançamento é obrigatório");
        if (createdAt == null) throw new ValidationException("Data do lançamento é obrigatória");
    }

    /** Registra a movimentação que acabou de ser aplicada na conta (o saldo já é o pós-operação). */
    public static AccountEntry of(Account account, EntryType type, Money amount, UUID referenceId) {
        return new AccountEntry(UUID.randomUUID(), account.getId(), type, amount, account.getBalance(),
                referenceId, DomainTime.now());
    }
}
