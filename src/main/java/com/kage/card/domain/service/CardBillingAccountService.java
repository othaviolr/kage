package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

/**
 * Porta do contexto Card para o contexto Account: debitar o pagamento da fatura na conta vinculada
 * ao cartão. Roda na mesma transação do PayInvoice — a fatura só vira PAID se o débito acontecer.
 * A implementação vive em account.infrastructure.
 */
public interface CardBillingAccountService {

    /**
     * @throws com.kage.shared.domain.exception.NotFoundException     se a conta não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se a conta não está ativa ou não tem saldo
     */
    void debitInvoicePayment(UUID accountId, Money amount, UUID invoiceId);

    /**
     * Credita o cashback na conta (lançamento no extrato). Conta que não está ativa (bloqueada,
     * encerrada) não recebe: devolve {@code false} e o cashback vai para a fatura.
     */
    boolean creditCashback(UUID accountId, Money amount, UUID sourceInvoiceId);
}
