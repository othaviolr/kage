package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.service.AccountCustomerValidationService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAccountTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    AccountCustomerValidationService customerValidation;

    @InjectMocks
    CreateAccount createAccount;

    @Test
    void execute_deveValidarClienteEAbrirContaAtiva() {
        UUID customerId = UUID.randomUUID();
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateAccount.Output output = createAccount.execute(new CreateAccount.Input(customerId, "checking"));

        assertThat(output.type()).isEqualTo("CHECKING");
        assertThat(output.status()).isEqualTo("ACTIVE");
        InOrder order = inOrder(customerValidation, accountRepository);
        order.verify(customerValidation).validateCanOpenAccount(customerId);
        order.verify(accountRepository).save(any(Account.class));
    }

    @Test
    void execute_deveLancarValidationException_quandoClienteNaoInformado() {
        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(null, "CHECKING")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cliente é obrigatório");

        verifyNoInteractions(customerValidation, accountRepository);
    }

    @Test
    void execute_deveLancarValidationException_quandoTipoInvalido_semConsultarCliente() {
        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(UUID.randomUUID(), "POUPANCA")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Tipo de conta inválido");

        verifyNoInteractions(customerValidation);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void execute_devePropagarNotFoundException_eNaoSalvar_quandoClienteNaoExiste() {
        UUID customerId = UUID.randomUUID();
        doThrow(new NotFoundException("Cliente não encontrado")).when(customerValidation).validateCanOpenAccount(customerId);

        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")))
                .isInstanceOf(NotFoundException.class);

        verify(accountRepository, never()).save(any());
    }

    @Test
    void execute_devePropagarBusinessRuleException_eNaoSalvar_quandoClienteNaoElegivel() {
        UUID customerId = UUID.randomUUID();
        doThrow(new BusinessRuleException("KYC do cliente não foi aprovado"))
                .when(customerValidation).validateCanOpenAccount(customerId);

        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(customerId, "SAVINGS")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");

        verify(accountRepository, never()).save(any());
    }
}
