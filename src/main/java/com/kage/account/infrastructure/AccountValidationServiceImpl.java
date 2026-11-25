package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;
import java.util.function.Supplier;

public class AccountValidationServiceImpl implements AccountValidationService {

    private final AccountRepository accountRepository;

    public AccountValidationServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void validateBalanceAndLimits(UUID accountId, Money amount, Supplier<PixLimitUsage> usage) {
        // trava primeiro, soma depois: ver contrato em AccountValidationService
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        PixLimitUsage used = usage.get();
        account.checkPixLimits(amount, used.sentToday(), used.sentTonight(), used.nightTime());
    }
}
