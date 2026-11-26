package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.loan.domain.service.LoanAccountGateway;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lado Account do empréstimo: liberar, cobrar e devolver, com lançamento no extrato. O @Version da
 * conta derruba dois débitos simultâneos (parcela + PIX) com 409.
 */
public class LoanAccountGatewayImpl implements LoanAccountGateway {

    /** O que conta como renda: dinheiro que entra de fora (não resgate de caixinha nem o próprio empréstimo). */
    private static final List<EntryType> INCOME = List.of(EntryType.DEPOSIT, EntryType.PIX_RECEIVED, EntryType.BOLETO_RECEIVED);

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;

    public LoanAccountGatewayImpl(AccountRepository accountRepository, AccountEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    public Optional<UUID> customerOf(UUID accountId) {
        return accountRepository.findById(accountId).map(Account::getCustomerId);
    }

    @Override
    public Money incomeSince(UUID accountId, LocalDateTime since) {
        return INCOME.stream().map(type -> entryRepository.sumByTypeSince(accountId, type, since))
                .reduce(Money.ZERO, Money::add);
    }

    @Override
    public void disburse(UUID accountId, Money amount, UUID loanId) {
        Account account = find(accountId);
        account.credit(amount);
        entryRepository.save(AccountEntry.of(accountRepository.save(account), EntryType.LOAN_DISBURSEMENT, amount, loanId));
    }

    @Override
    public void collect(UUID accountId, Money amount, UUID loanId) {
        Account account = find(accountId);
        account.debit(amount);
        entryRepository.save(AccountEntry.of(accountRepository.save(account), EntryType.LOAN_PAYMENT, amount, loanId));
    }

    @Override
    public void returnDisbursement(UUID accountId, Money amount, UUID loanId) {
        Account account = find(accountId);
        account.debit(amount);
        entryRepository.save(AccountEntry.of(accountRepository.save(account), EntryType.LOAN_RETURN, amount, loanId));
    }

    private Account find(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }
}
