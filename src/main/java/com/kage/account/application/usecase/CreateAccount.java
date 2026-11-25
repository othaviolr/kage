package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountCustomerValidationService;
import com.kage.account.domain.valueobject.AccountCheckDigit;
import com.kage.shared.domain.exception.ValidationException;

import java.util.UUID;

public class CreateAccount {

    public record Input(UUID customerId, String accountType) {}
    public record Output(UUID accountId, String accountNumber, String accountDigit, String branch, String type, String status) {}

    private final AccountRepository accountRepository;
    private final AccountCustomerValidationService customerValidation;

    public CreateAccount(AccountRepository accountRepository, AccountCustomerValidationService customerValidation) {
        this.accountRepository = accountRepository;
        this.customerValidation = customerValidation;
    }

    public Output execute(Input input) {
        if (input.customerId() == null) {
            throw new ValidationException("Cliente é obrigatório");
        }
        AccountType type = parseAccountType(input.accountType());
        customerValidation.validateCanOpenAccount(input.customerId());
        String accountNumber = accountRepository.nextAccountNumber();

        Account account = Account.create(input.customerId(), type, accountNumber, AccountCheckDigit.of(accountNumber));
        Account saved = accountRepository.save(account);

        return new Output(saved.getId(), saved.getAccountNumber(), saved.getAccountDigit(), saved.getBranch(), saved.getType().name(), saved.getStatus().name());
    }

    private AccountType parseAccountType(String type) {
        if (type == null || type.isBlank()) {
            throw new ValidationException("Tipo de conta é obrigatório");
        }
        try {
            return AccountType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Tipo de conta inválido: " + type);
        }
    }
}