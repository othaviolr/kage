package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockCardTest {

    @Mock
    CardRepository cardRepository;

    @InjectMocks
    BlockCard blockCard;

    private Card card;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void execute_deveBloquearCartaoAtivo() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BlockCard.Output output = blockCard.execute(new BlockCard.Input(card.getId()));

        assertThat(output.status()).isEqualTo(CardStatus.BLOCKED.name());
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> blockCard.execute(new BlockCard.Input(cardId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void execute_devePropagarBusinessRuleException_quandoJaBloqueado() {
        card.block();
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> blockCard.execute(new BlockCard.Input(card.getId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está bloqueado");
    }
}
