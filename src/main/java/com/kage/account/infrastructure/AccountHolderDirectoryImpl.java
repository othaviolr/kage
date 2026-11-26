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
        return accountRepository.findById(accountId).map(account -> {
            var person = customerRepository.findById(account.getCustomerId()).map(c -> c.getPersonalInfo());
            return new Holder(account.getId(), person.map(p -> p.fullName()).orElse(null),
                    person.map(p -> p.cpf().value()).orElse(null), account.getBranch(),
                    account.getAccountNumber() + "-" + account.getAccountDigit(), account.getStatus() == AccountStatus.ACTIVE);
        });
    }
}
