package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountSavingsCheck;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

public class CloseAccount {

    public record Input(UUID accountId) {}
    public record Output(UUID accountId, String status) {}

    private final AccountRepository accountRepository;
    private final AccountSavingsCheck savingsCheck;

    public CloseAccount(AccountRepository accountRepository, AccountSavingsCheck savingsCheck) {
        this.accountRepository = accountRepository;
        this.savingsCheck = savingsCheck;
    }

    public Output execute(Input input) {
        Account account = accountRepository.findById(input.accountId())
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));

        // saldo zero na conta não basta: dinheiro guardado em caixinha também é do cliente
        if (savingsCheck.hasActiveSavingsBoxes(account.getId())) {
            throw new BusinessRuleException("Encerre as caixinhas (resgatando o saldo) antes de encerrar a conta");
        }
        account.close();
        accountRepository.save(account);

        return new Output(account.getId(), account.getStatus().name());
    }
}
