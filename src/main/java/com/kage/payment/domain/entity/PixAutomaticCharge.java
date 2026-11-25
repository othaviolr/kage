package com.kage.payment.domain.entity;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cobrança de um ciclo do PIX Automático. Aceita, vira um PIX agendado comum para o vencimento
 * ({@code pixTransactionId}): a situação dela (agendada, paga, recusada, cancelada) é a desse PIX.
 */
public record PixAutomaticCharge(UUID id, UUID authorizationId, int cycle, Money amount, LocalDate dueDate,
                                 UUID pixTransactionId, LocalDateTime createdAt) {

    public static PixAutomaticCharge of(PixAutomaticAuthorization authorization, int cycle, Money amount,
                                        LocalDate dueDate, UUID pixTransactionId, LocalDateTime now) {
        return new PixAutomaticCharge(UUID.randomUUID(), authorization.getId(), cycle, amount, dueDate, pixTransactionId, now);
    }
}
