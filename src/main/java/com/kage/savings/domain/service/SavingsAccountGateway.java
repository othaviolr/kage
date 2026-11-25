package com.kage.savings.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta do Savings para o Account: tirar e devolver dinheiro da conta corrente, com lançamento no
 * extrato, na mesma transação da caixinha. A implementação vive em account.infrastructure.
 */
public interface SavingsAccountGateway {

    /** @return o dono da conta; vazio se ela não existe */
    Optional<UUID> customerOf(UUID accountId);

    /** Guardar: debita a conta. @throws com.kage.shared.domain.exception.BusinessRuleException sem saldo / conta não ativa */
    void moveToSavings(UUID accountId, Money amount, UUID boxId);

    /** Resgatar: credita a conta. @throws com.kage.shared.domain.exception.BusinessRuleException conta não ativa */
    void moveFromSavings(UUID accountId, Money amount, UUID boxId);
}
