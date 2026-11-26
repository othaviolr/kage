package com.kage.account.infrastructure;

import com.kage.account.application.usecase.LimitChanges;
import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.IntFunction;

public class AccountValidationServiceImpl implements AccountValidationService {

    private final AccountRepository accountRepository;
    private final LimitChanges limitChanges;
    private final Clock clock;

    public AccountValidationServiceImpl(AccountRepository accountRepository, LimitChanges limitChanges, Clock clock) {
        this.accountRepository = accountRepository;
        this.limitChanges = limitChanges;
        this.clock = clock;
    }

    @Override
    public void validateBalanceAndLimits(UUID accountId, Money amount, IntFunction<PixLimitUsage> usage) {
        // trava primeiro, soma depois: ver contrato em AccountValidationService
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        // aumento de limite que completou 24h vale já neste PIX, sem esperar o job
        if (limitChanges.applyDue(account, LocalDateTime.now(clock))) {
            account = accountRepository.save(account);
        }

        PixLimitUsage used = usage.apply(account.getLimits().pixNightStartHour());
        account.checkPixLimits(amount, used.sentToday(), used.sentTonight(), used.nightTime());
    }
}
