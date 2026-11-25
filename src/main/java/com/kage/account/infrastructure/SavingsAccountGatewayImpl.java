package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.savings.domain.service.SavingsAccountGateway;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.util.Optional;
import java.util.UUID;

/**
 * Lado Account das caixinhas: debitar ao guardar, creditar ao resgatar, com lançamento no extrato.
 * Roda na transação do use case da caixinha — ou a conta e a caixinha mudam juntas, ou nenhuma muda.
 */
public class SavingsAccountGatewayImpl implements SavingsAccountGateway {

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;

    public SavingsAccountGatewayImpl(AccountRepository accountRepository, AccountEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    public Optional<UUID> customerOf(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::getCustomerId);
    }

    @Override
    public void moveToSavings(UUID accountId, Money amount, UUID boxId) {
        Account account = find(accountId);
        account.debit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.SAVINGS_DEPOSIT, amount, boxId));
    }

    @Override
    public void moveFromSavings(UUID accountId, Money amount, UUID boxId) {
        Account account = find(accountId);
        account.credit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.SAVINGS_WITHDRAWAL, amount, boxId));
    }

    private Account find(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }
}
