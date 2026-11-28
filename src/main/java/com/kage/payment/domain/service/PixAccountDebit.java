package com.kage.payment.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

/**
 * Porta do PIX para o Account: debita a conta na hora, com lançamento "PIX enviado" no extrato. Usada
 * quando quem recebe é o próprio Kage (cobrança paga pelo copia e cola), que não tem conta para creditar.
 */
public interface PixAccountDebit {

    /** @throws com.kage.shared.domain.exception.BusinessRuleException sem saldo ou conta não ativa */
    void debit(UUID accountId, Money amount, UUID referenceId);
}
