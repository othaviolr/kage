package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.service.CardAccountValidationService;
import com.kage.card.domain.service.CardCustomerValidationService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class IssueCardTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    CardCustomerValidationService customerValidation;

    @Mock
    CardAccountValidationService accountValidation;

    @InjectMocks
    IssueCard issueCard;

    @Test
    void execute_deveCriarCartaoAtivoComLimiteDisponivelIgualAoLimiteDeCredito() {
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IssueCard.Output output = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        assertThat(output.status()).isEqualTo("ACTIVE");
        assertThat(output.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(output.availableLimit()).isEqualByComparingTo("1000.00");
        assertThat(output.closingDay()).isEqualTo(10);
        assertThat(output.dueDay()).isEqualTo(20);
    }

    @Test
    void execute_deveLancarValidationException_quandoLimiteNulo() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), null, 10, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Limite de crédito");

        verifyNoInteractions(cardRepository, customerValidation, accountValidation);
    }

    @Test
    void execute_deveLancarValidationException_quandoLimiteZeroOuNegativo() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, 10, 20)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(cardRepository, customerValidation, accountValidation);
    }

    @Test
    void execute_devePropagarValidationException_quandoDiaDeFechamentoInvalido() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 29, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Dia de fechamento");
    }

    @Test
    void execute_deveValidarClienteEContaAntesDeSalvar() {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        issueCard.execute(new IssueCard.Input(customerId, accountId, new BigDecimal("1000.00"), 10, 20));

        InOrder order = inOrder(customerValidation, accountValidation, cardRepository);
        order.verify(customerValidation).validateCanIssueCard(customerId);
        order.verify(accountValidation).validateCanLinkCard(accountId, customerId);
        order.verify(cardRepository).save(any(Card.class));
    }

    @Test
    void execute_deveLancarValidationException_quandoClienteOuContaNaoInformados() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                null, UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Cliente é obrigatório");
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), null, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Conta é obrigatória");

        verifyNoInteractions(cardRepository, customerValidation, accountValidation);
    }

    @Test
    void execute_devePropagarNotFoundException_eNaoSalvar_quandoClienteNaoExiste() {
        UUID customerId = UUID.randomUUID();
        doThrow(new NotFoundException("Cliente não encontrado")).when(customerValidation).validateCanIssueCard(customerId);

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cliente não encontrado");

        verifyNoInteractions(accountValidation);
        verify(cardRepository, never()).save(any());
    }

    @Test
    void execute_devePropagarBusinessRuleException_eNaoSalvar_quandoClienteNaoElegivel() {
        UUID customerId = UUID.randomUUID();
        doThrow(new BusinessRuleException("Cliente não está ativo")).when(customerValidation).validateCanIssueCard(customerId);

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cliente não está ativo");

        verify(cardRepository, never()).save(any());
    }

    @Test
    void execute_devePropagarExcecaoDaConta_eNaoSalvar_quandoContaInvalida() {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        doThrow(new BusinessRuleException("Conta não pertence ao cliente"))
                .when(accountValidation).validateCanLinkCard(accountId, customerId);

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, accountId, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pertence ao cliente");

        verify(cardRepository, never()).save(any());
    }
}
