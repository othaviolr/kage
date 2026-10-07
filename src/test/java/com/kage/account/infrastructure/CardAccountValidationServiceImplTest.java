package com.kage.account.infrastructure;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.AccountType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardAccountValidationServiceImplTest {

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    CardAccountValidationServiceImpl validationService;

    private UUID customerId;
    private Account account;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        account = Account.create(customerId, AccountType.CHECKING, "12345", "5");
    }

    private void stubFound() {
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    }

    @Test
    void deveAceitarConta_quandoAtivaEDoCliente() {
        stubFound();

        assertThatCode(() -> validationService.validateCanLinkCard(account.getId(), customerId))
                .doesNotThrowAnyException();
    }

    @Test
    void deveLancarNotFoundException_quandoContaNaoExiste() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validationService.validateCanLinkCard(accountId, customerId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Conta não encontrada");
    }

    @Test
    void deveLancarBusinessRuleException_quandoContaEDeOutroCliente() {
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanLinkCard(account.getId(), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pertence ao cliente");
    }

    @Test
    void deveLancarBusinessRuleException_quandoContaBloqueada() {
        account.block();
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanLinkCard(account.getId(), customerId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativa");
    }

    @Test
    void deveLancarBusinessRuleException_quandoContaEncerrada() {
        account.close();
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanLinkCard(account.getId(), customerId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativa");
    }
}
