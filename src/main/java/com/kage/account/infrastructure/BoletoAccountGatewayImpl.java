package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.boleto.domain.service.BoletoAccountGateway;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.util.Optional;
import java.util.UUID;

/** Lado Account dos boletos: roda na transação do pagamento, com lançamento no extrato. */
public class BoletoAccountGatewayImpl implements BoletoAccountGateway {

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;
    private final CustomerRepository customerRepository;

    public BoletoAccountGatewayImpl(AccountRepository accountRepository, AccountEntryRepository entryRepository,
                                    CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public Optional<AccountInfo> account(UUID accountId) {
        return accountRepository.findById(accountId).map(a -> {
            var holder = customerRepository.findById(a.getCustomerId()).map(c -> c.getPersonalInfo());
            return new AccountInfo(a.getId(), a.getCustomerId(), a.getBranch(), a.getAccountNumber(),
                    a.getStatus() == AccountStatus.ACTIVE, holder.map(p -> p.fullName()).orElse(null),
                    holder.map(p -> p.cpf().value()).orElse(null));
        });
    }

    @Override
    public void debitPayment(UUID accountId, Money amount, UUID paymentId) {
        Account account = find(accountId);
        account.debit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.BOLETO_PAYMENT, amount, paymentId));
    }

    @Override
    public void creditReceived(UUID accountId, Money amount, UUID boletoId) {
        Account account = find(accountId);
        account.credit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.BOLETO_RECEIVED, amount, boletoId));
    }

    private Account find(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }
}
