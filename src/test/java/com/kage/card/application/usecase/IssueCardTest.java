package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueCardTest {

    @Mock
    CardRepository cardRepository;

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

        verifyNoInteractions(cardRepository);
    }

    @Test
    void execute_deveLancarValidationException_quandoLimiteZeroOuNegativo() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, 10, 20)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(cardRepository);
    }

    @Test
    void execute_devePropagarValidationException_quandoDiaDeFechamentoInvalido() {
        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 29, 20)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Dia de fechamento");
    }
}
