package com.kage.account.application.usecase;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Porta de saída dos eventos de movimentação da conta. A implementação grava no outbox, então
 * precisa ser chamada dentro da transação do use case.
 */
public interface AccountEventPublisher {

    void publishDepositMade(UUID accountId, BigDecimal amount, BigDecimal newBalance);

    void publishWithdrawalMade(UUID accountId, BigDecimal amount, BigDecimal newBalance);
}
