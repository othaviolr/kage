package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetCardTest {

    @Mock
    CardRepository cardRepository;

    @InjectMocks
    GetCard getCard;

    private Card card;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void execute_deveRetornarDadosDoCartao() {
        card.authorizePurchase(Money.of("300.00"));
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        GetCard.Output output = getCard.execute(new GetCard.Input(card.getId()));

        assertThat(output.creditLimit()).isEqualByComparingTo("1000.00");
        assertThat(output.usedLimit()).isEqualByComparingTo("300.00");
        assertThat(output.availableLimit()).isEqualByComparingTo("700.00");
        assertThat(output.status()).isEqualTo("ACTIVE");
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> getCard.execute(new GetCard.Input(cardId)))
                .isInstanceOf(NotFoundException.class);
    }
}
