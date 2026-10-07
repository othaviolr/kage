package com.kage.card.domain.service;

import java.util.UUID;

/**
 * Porta do contexto Card para o contexto Customer: quem pode ter cartão emitido. A implementação
 * vive em customer.infrastructure (mesmo padrão do AccountValidationService do Pix).
 */
public interface CardCustomerValidationService {

    /**
     * @throws com.kage.shared.domain.exception.NotFoundException     se o cliente não existe
     * @throws com.kage.shared.domain.exception.BusinessRuleException se o KYC não foi aprovado ou o cliente não está ativo
     */
    void validateCanIssueCard(UUID customerId);
}
