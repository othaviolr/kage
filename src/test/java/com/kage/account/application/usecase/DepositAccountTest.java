package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
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
class DepositAccountTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    DepositAccount depositAccount;

    @Test
    void execute_deveCreditarConta_quandoValorValido() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DepositAccount.Output output = depositAccount.execute(new DepositAccount.Input(account.getId(), new BigDecimal("200.00")));

        assertThat(output.balance()).isEqualByComparingTo("200.00");
        assertThat(output.availableBalance()).isEqualByComparingTo("200.00");
    }

    @Test
    void execute_deveLancarValidationException_quandoValorZeroOuNegativo() {
        assertThatThrownBy(() -> depositAccount.execute(new DepositAccount.Input(UUID.randomUUID(), BigDecimal.ZERO)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(accountRepository);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoContaNaoExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("50.00"))))
                .isInstanceOf(NotFoundException.class);
    }
}
