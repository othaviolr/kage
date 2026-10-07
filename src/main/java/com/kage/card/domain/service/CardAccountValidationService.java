package com.kage.card.domain.service;

import java.util.UUID;

/**
 * Porta do contexto Card para o contexto Account: a conta onde a fatura será debitada precisa
 * existir, estar ativa e pertencer ao cliente que recebe o cartão. A implementação vive em
 * account.infrastructure.
 */
public interface CardAccountValidationService {

    /**
     * @throws com.kage.shared.domain.exception.NotFoundException     se a conta não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se a conta não é do cliente ou não está ativa
     */
    void validateCanLinkCard(UUID accountId, UUID customerId);
}
