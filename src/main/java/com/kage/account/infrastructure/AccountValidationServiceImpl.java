package com.kage.account.infrastructure;

import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.entity.Account;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

public class AccountValidationServiceImpl implements AccountValidationService {

    private final AccountRepository accountRepository;

    public AccountValidationServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void validateBalanceAndLimits(UUID accountId, Money amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new DomainException("Conta não encontrada"));

        if (account.getAvailableBalance().isLessThan(amount)) {
            throw new DomainException("Saldo insuficiente para realizar o PIX");
        }

        if (amount.isGreaterThan(account.getLimits().pixDailyLimit())) {
            throw new DomainException("Valor excede o limite diário de PIX");
        }
    }
}