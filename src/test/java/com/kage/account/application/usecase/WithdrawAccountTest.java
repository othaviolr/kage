package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.entity.AccountEntry;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.enums.EntryType;
import com.kage.account.domain.repository.AccountEntryRepository;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawAccountTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 15, 30);

    @Mock
    AccountRepository accountRepository;

    @Mock
    AccountEntryRepository entryRepository;

    @Mock
    AccountEventPublisher eventPublisher;

    WithdrawAccount withdrawAccount;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        withdrawAccount = new WithdrawAccount(accountRepository, entryRepository, eventPublisher, clock);
    }

    private Account accountWithBalance(String balance) {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        account.credit(Money.of(balance));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }

    @Test
    void execute_deveDebitarContaGravarLancamentoEPublicarEvento_quandoDentroDoLimite() {
        Account account = accountWithBalance("500.00");
        when(entryRepository.sumByTypeSince(account.getId(), EntryType.WITHDRAWAL, NOW.toLocalDate().atStartOfDay()))
                .thenReturn(Money.ZERO);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WithdrawAccount.Output output = withdrawAccount.execute(new WithdrawAccount.Input(account.getId(), new BigDecimal("200.00")));

        assertThat(output.balance()).isEqualByComparingTo("300.00");
        ArgumentCaptor<AccountEntry> entry = ArgumentCaptor.forClass(AccountEntry.class);
        verify(entryRepository).save(entry.capture());
        assertThat(entry.getValue().type()).isEqualTo(EntryType.WITHDRAWAL);
        assertThat(entry.getValue().amount()).isEqualTo(Money.of("200.00"));
        assertThat(entry.getValue().balanceAfter()).isEqualTo(Money.of("300.00"));
        verify(eventPublisher).publishWithdrawalMade(eq(account.getId()), any(), any());
    }

    @Test
    void execute_deveRecusar_quandoSaquesDoDiaJaConsumiramOLimite() {
        Account account = accountWithBalance("5000.00");
        when(entryRepository.sumByTypeSince(account.getId(), EntryType.WITHDRAWAL, NOW.toLocalDate().atStartOfDay()))
                .thenReturn(Money.of("900.00"));

        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(account.getId(), new BigDecimal("200.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de saque");

        verify(accountRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void execute_deveLancarValidationException_quandoValorZeroOuNegativo() {
        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(UUID.randomUUID(), new BigDecimal("-5.00"))))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(accountRepository);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoContaNaoExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(accountId, new BigDecimal("50.00"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void execute_deveLancarBusinessRuleException_quandoSaldoInsuficiente() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(entryRepository.sumByTypeSince(any(), any(), any())).thenReturn(Money.ZERO);

        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(account.getId(), new BigDecimal("10.00"))))
                .isInstanceOf(BusinessRuleException.class);
    }
}
