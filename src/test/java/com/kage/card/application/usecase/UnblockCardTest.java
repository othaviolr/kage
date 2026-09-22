package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
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
class UnblockCardTest {

    @Mock
    CardRepository cardRepository;

    @InjectMocks
    UnblockCard unblockCard;

    private Card card;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
        card.block();
    }

    @Test
    void execute_deveDesbloquearCartaoBloqueado() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UnblockCard.Output output = unblockCard.execute(new UnblockCard.Input(card.getId()));

        assertThat(output.status()).isEqualTo(CardStatus.ACTIVE.name());
    }

    @Test
    void execute_devePropagarBusinessRuleException_quandoCartaoNaoEstaBloqueado() {
        card.unblock();
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> unblockCard.execute(new UnblockCard.Input(card.getId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está bloqueado");
    }
}
