package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.domain.service.PixAccountDebit;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

/** Lado Account do PIX pago a uma cobrança do Kage: roda na transação do pagamento. */
public class PixAccountDebitImpl implements PixAccountDebit {

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;

    public PixAccountDebitImpl(AccountRepository accountRepository, AccountEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    public void debit(UUID accountId, Money amount, UUID referenceId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));
        account.debit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.PIX_SENT, amount, referenceId));
    }
}
