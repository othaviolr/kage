package com.kage.savings.infrastructure;

import com.kage.account.domain.service.AccountSavingsCheck;
import com.kage.savings.domain.repository.SavingsBoxRepository;

import java.util.UUID;

public class AccountSavingsCheckImpl implements AccountSavingsCheck {

    private final SavingsBoxRepository repository;

    public AccountSavingsCheckImpl(SavingsBoxRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean hasActiveSavingsBoxes(UUID accountId) {
        return repository.hasActiveBoxes(accountId);
    }
}
