package com.kage.loan.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta do empréstimo para a conta: liberar e receber dinheiro com lançamento no extrato, na mesma
 * transação, e a renda estimada pela movimentação. A implementação vive em account.infrastructure.
 */
public interface LoanAccountGateway {

    /** @return o dono da conta; vazio se ela não existe */
    Optional<UUID> customerOf(UUID accountId);

    /** Entradas de dinheiro de fora (depósitos, PIX recebidos, boletos recebidos) desde {@code since}. */
    Money incomeSince(UUID accountId, LocalDateTime since);

    /** Libera o empréstimo: credita a conta. */
    void disburse(UUID accountId, Money amount, UUID loanId);

    /** Paga parcela(s): debita a conta. @throws com.kage.shared.domain.exception.BusinessRuleException sem saldo */
    void collect(UUID accountId, Money amount, UUID loanId);

    /** Arrependimento: o cliente devolve o que recebeu. @throws BusinessRuleException sem saldo */
    void returnDisbursement(UUID accountId, Money amount, UUID loanId);
}
