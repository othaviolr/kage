package com.kage.account.infrastructure;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;

import java.util.Optional;
import java.util.UUID;

public class AccountHolderDirectoryImpl implements AccountHolderDirectory {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public AccountHolderDirectoryImpl(AccountRepository accountRepository, CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<Holder> holderOf(UUID accountId) {
        return accountRepository.findById(accountId).map(account -> new Holder(account.getId(),
                customerRepository.findById(account.getCustomerId()).map(c -> c.getPersonalInfo().fullName()).orElse(null),
                account.getStatus() == AccountStatus.ACTIVE));
    }
}
