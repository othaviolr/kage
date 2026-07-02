package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.ValidationException;

import java.util.UUID;

public class CreateAccount {

    public record Input(UUID customerId, String accountType) {}
    public record Output(UUID accountId, String accountNumber, String accountDigit, String branch, String type, String status) {}

    private final AccountRepository accountRepository;

    public CreateAccount(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Output execute(Input input) {
        AccountType type = parseAccountType(input.accountType());
        String accountNumber = generateUniqueAccountNumber();
        String accountDigit = generateDigit(accountNumber);

        Account account = Account.create(input.customerId(), type, accountNumber, accountDigit);
        Account saved = accountRepository.save(account);

        return new Output(saved.getId(), saved.getAccountNumber(), saved.getAccountDigit(), saved.getBranch(), saved.getType().name(), saved.getStatus().name());
    }

    private AccountType parseAccountType(String type) {
        try {
            return AccountType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Tipo de conta inválido: " + type);
        }
    }

    private String generateUniqueAccountNumber() {
        String number;
        do {
            number = String.format("%05d", (int) (Math.random() * 100000));
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }

    private String generateDigit(String accountNumber) {
        int sum = accountNumber.chars().map(Character::getNumericValue).sum();
        return String.valueOf(sum % 10);
    }
}