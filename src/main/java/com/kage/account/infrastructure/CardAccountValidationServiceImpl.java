package com.kage.account.infrastructure;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.service.CardAccountValidationService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

/**
 * Lado Account da emissão de cartão: a conta existe, pertence ao cliente que vai receber o cartão
 * e está ativa (a fatura será debitada dela).
 */
public class CardAccountValidationServiceImpl implements CardAccountValidationService {

    private final AccountRepository accountRepository;

    public CardAccountValidationServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void validateCanLinkCard(UUID accountId, UUID customerId) {
        var account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        if (!account.getCustomerId().equals(customerId)) {
            throw new BusinessRuleException("Conta não pertence ao cliente");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Conta não está ativa");
        }
    }
}
