package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.valueobject.Limits;
import com.kage.account.domain.valueobject.Money;
import com.kage.shared.domain.exception.DomainException;

import java.math.BigDecimal;
import java.util.UUID;

public class UpdateLimits {

    public record Input(UUID accountId, BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit,
                        BigDecimal pixDailyLimit, BigDecimal pixNightLimit) {}
    public record Output(UUID accountId, BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit,
                         BigDecimal pixDailyLimit, BigDecimal pixNightLimit) {}

    private final AccountRepository accountRepository;

    public UpdateLimits(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Output execute(Input input) {
        Account account = accountRepository.findById(input.accountId()).orElseThrow(() -> new DomainException("Conta não encontrada"));

        Limits newLimits = new Limits(Money.of(input.dailyTransferLimit()), Money.of(input.monthlyTransferLimit()), Money.of(input.pixDailyLimit()), Money.of(input.pixNightLimit()));

        account.updateLimits(newLimits);
        accountRepository.save(account);

        return new Output(
                account.getId(),
                account.getLimits().dailyTransferLimit().amount(),
                account.getLimits().monthlyTransferLimit().amount(),
                account.getLimits().pixDailyLimit().amount(),
                account.getLimits().pixNightLimit().amount()
        );
    }
}