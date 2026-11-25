package com.kage.boleto.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta do Boleto para o Account: dados da conta (e do titular, para o pagador conferir quem vai
 * receber) e débito/crédito com lançamento no extrato, na transação do pagamento.
 */
public interface BoletoAccountGateway {

    record AccountInfo(UUID accountId, UUID customerId, String branch, String accountNumber, boolean active,
                       String holderName, String holderDocument) {}

    Optional<AccountInfo> account(UUID accountId);

    /** @throws com.kage.shared.domain.exception.BusinessRuleException sem saldo / conta não ativa */
    void debitPayment(UUID accountId, Money amount, UUID paymentId);

    void creditReceived(UUID accountId, Money amount, UUID boletoId);
}
