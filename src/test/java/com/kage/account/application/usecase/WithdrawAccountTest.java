package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WithdrawAccountTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    WithdrawAccount withdrawAccount;

    @Test
    void execute_deveDebitarConta_quandoValorValidoDentroDoLimite() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        account.credit(Money.of("500.00"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WithdrawAccount.Output output = withdrawAccount.execute(new WithdrawAccount.Input(account.getId(), new BigDecimal("200.00")));

        assertThat(output.balance()).isEqualByComparingTo("300.00");
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

        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(account.getId(), new BigDecimal("10.00"))))
                .isInstanceOf(BusinessRuleException.class);
    }
}
