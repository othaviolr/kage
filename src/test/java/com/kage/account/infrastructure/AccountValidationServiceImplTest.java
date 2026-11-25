package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountValidationServiceImplTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    AccountValidationServiceImpl validation;

    @Test
    void validate_deveTravarAContaAntesDeCalcularOUsoDoLimite() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        account.credit(Money.of("1000.00"));
        List<String> calls = new ArrayList<>();
        when(accountRepository.findByIdForUpdate(account.getId())).thenAnswer(invocation -> {
            calls.add("lock");
            return Optional.of(account);
        });

        validation.validateBalanceAndLimits(account.getId(), Money.of("100.00"), () -> {
            calls.add("usage");
            return new PixLimitUsage(Money.ZERO, Money.ZERO, false);
        });

        // sem essa ordem, dois PIX simultâneos somariam o uso antes de um ver o outro
        assertThat(calls).containsExactly("lock", "usage");
        verify(accountRepository, never()).findById(account.getId());
    }

    @Test
    void validate_deveRecusar_quandoUsoDoDiaEstouraOLimite() {
        Account account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
        account.credit(Money.of("5000.00"));
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> validation.validateBalanceAndLimits(account.getId(), Money.of("100.00"),
                () -> new PixLimitUsage(Money.of("1950.00"), Money.ZERO, false)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de PIX");
    }

    @Test
    void validate_deveLancarNotFound_semCalcularUso_quandoContaNaoExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validation.validateBalanceAndLimits(accountId, Money.of("10.00"), () -> {
            throw new AssertionError("não deveria calcular o uso");
        })).isInstanceOf(NotFoundException.class);
    }
}
