package com.kage.account.domain.service;

import java.util.UUID;

/**
 * Porta do contexto Account para o contexto Customer: quem pode abrir conta. A implementação vive
 * em customer.infrastructure.
 */
public interface AccountCustomerValidationService {

    /**
     * @throws com.kage.shared.domain.exception.NotFoundException     se o cliente não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se o KYC não foi aprovado ou o cliente não está ativo
     */
    void validateCanOpenAccount(UUID customerId);
}
