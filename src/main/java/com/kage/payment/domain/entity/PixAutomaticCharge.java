package com.kage.payment.domain.entity;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Cobrança de um ciclo do PIX Automático. Cada tentativa de pagamento (a original e as novas, se a
 * autorização permitir) é um PIX agendado comum ({@link Attempt}); a situação da cobrança é a da
 * tentativa mais recente.
 */
public record PixAutomaticCharge(UUID id, UUID authorizationId, int cycle, Money amount, LocalDate dueDate,
                                 LocalDateTime createdAt) {

    public static PixAutomaticCharge of(PixAutomaticAuthorization authorization, int cycle, Money amount,
                                        LocalDate dueDate, LocalDateTime now) {
        return new PixAutomaticCharge(UUID.randomUUID(), authorization.getId(), cycle, amount, dueDate, now);
    }

    /** @param number 1 = a do vencimento; 2 a 4 = novas tentativas */
    public record Attempt(UUID id, UUID chargeId, int number, LocalDate scheduledDate, UUID pixTransactionId,
                          LocalDateTime createdAt) {

        public static Attempt of(PixAutomaticCharge charge, int number, LocalDate date, UUID pixTransactionId,
                                 LocalDateTime now) {
            return new Attempt(UUID.randomUUID(), charge.id(), number, date, pixTransactionId, now);
        }
    }
}
