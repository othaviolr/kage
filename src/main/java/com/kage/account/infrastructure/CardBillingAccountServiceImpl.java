package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.util.UUID;

/**
 * Lado Account do cartão: o pagamento de fatura debita a conta e grava o lançamento INVOICE_PAYMENT; o
 * cashback escolhido para a conta é creditado como CARD_CASHBACK. Dois
 * débitos simultâneos na mesma conta (ex.: fatura + saque) não passam juntos: o @Version da conta
 * derruba o segundo com 409.
 */
public class CardBillingAccountServiceImpl implements CardBillingAccountService {

    private final AccountRepository accountRepository;
    private final AccountEntryRepository entryRepository;

    public CardBillingAccountServiceImpl(AccountRepository accountRepository, AccountEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Override
    public void debitInvoicePayment(UUID accountId, Money amount, UUID invoiceId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Conta vinculada ao cartão não encontrada"));

        account.debit(amount);
        Account saved = accountRepository.save(account);
        entryRepository.save(AccountEntry.of(saved, EntryType.INVOICE_PAYMENT, amount, invoiceId));
    }

    @Override
    public boolean creditCashback(UUID accountId, Money amount, UUID sourceInvoiceId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) return false;
        account.credit(amount);
        entryRepository.save(AccountEntry.of(accountRepository.save(account), EntryType.CARD_CASHBACK, amount, sourceInvoiceId));
        return true;
    }
}
