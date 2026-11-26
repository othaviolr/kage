package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Cancela o cartão físico; os cartões virtuais dele são apagados junto, na mesma transação. */
public class CancelCard {

    public record Input(UUID cardId) {}
    public record Output(UUID cardId, String status) {}

    private final CardRepository cardRepository;
    private final VirtualCards virtualCards;

    public CancelCard(CardRepository cardRepository) {
        this(cardRepository, null);
    }

    public CancelCard(CardRepository cardRepository, VirtualCards virtualCards) {
        this.cardRepository = cardRepository;
        this.virtualCards = virtualCards;
    }

    @Transactional
    public Output execute(Input input) {
        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        card.cancel();
        Card saved = cardRepository.save(card);
        if (virtualCards != null) virtualCards.deleteAllOf(saved.getId());

        return new Output(saved.getId(), saved.getStatus().name());
    }
}
