package com.kage.account.infrastructure.persistence;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.valueobject.Limits;
import com.kage.account.domain.valueobject.Money;

public class AccountMapper {

    public static AccountJpaEntity toJpaEntity(Account account) {
        AccountJpaEntity entity = new AccountJpaEntity();
        entity.setId(account.getId());
        entity.setCustomerId(account.getCustomerId());
        entity.setAccountNumber(account.getAccountNumber());
        entity.setAccountDigit(account.getAccountDigit());
        entity.setBranch(account.getBranch());
        entity.setType(account.getType());
        entity.setBalance(account.getBalance().amount());
        entity.setAvailableBalance(account.getAvailableBalance().amount());
        entity.setDailyTransferLimit(account.getLimits().dailyTransferLimit().amount());
        entity.setMonthlyTransferLimit(account.getLimits().monthlyTransferLimit().amount());
        entity.setPixDailyLimit(account.getLimits().pixDailyLimit().amount());
        entity.setPixNightLimit(account.getLimits().pixNightLimit().amount());
        entity.setStatus(account.getStatus());
        entity.setOpenedAt(account.getOpenedAt());
        entity.setClosedAt(account.getClosedAt());
        entity.setUpdatedAt(account.getUpdatedAt());
        return entity;
    }

    public static Account toDomain(AccountJpaEntity entity) {
        Limits limits = new Limits(Money.of(entity.getDailyTransferLimit()), Money.of(entity.getMonthlyTransferLimit()), Money.of(entity.getPixDailyLimit()), Money.of(entity.getPixNightLimit()));

        return Account.reconstitute(
                entity.getId(),
                entity.getCustomerId(),
                entity.getAccountNumber(),
                entity.getAccountDigit(),
                entity.getBranch(),
                entity.getType(),
                Money.of(entity.getBalance()),
                Money.of(entity.getAvailableBalance()),
                limits,
                entity.getStatus(),
                entity.getOpenedAt(),
                entity.getClosedAt(),
                entity.getUpdatedAt()
        );
    }
}