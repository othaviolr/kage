package com.kage.notification.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.notification.domain.service.RecipientDirectory;

import java.util.Optional;
import java.util.UUID;

/** Leitura simples nos repositórios de Account e Card (mesmo processo, mesmo banco). */
public class RecipientDirectoryImpl implements RecipientDirectory {

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final CustomerRepository customerRepository;

    public RecipientDirectoryImpl(AccountRepository accountRepository, CardRepository cardRepository,
                                  CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<String> emailOf(UUID customerId) {
        return customerRepository.findById(customerId).map(c -> c.getPersonalInfo().email().value());
    }

    @Override
    public Optional<UUID> customerOfAccount(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::getCustomerId);
    }

    @Override
    public Optional<UUID> customerOfCard(UUID cardId) {
        return cardRepository.findById(cardId).map(Card::getCustomerId);
    }
}
