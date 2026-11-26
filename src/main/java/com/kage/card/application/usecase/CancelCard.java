package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Cancela o cartão; os plásticos e os cartões virtuais dele morrem junto, na mesma transação. */
public class CancelCard {

    public record Input(UUID cardId) {}
    public record Output(UUID cardId, String status) {}

    private final CardRepository cardRepository;
    private final VirtualCards virtualCards;
    private final CardPlastics plastics;

    public CancelCard(CardRepository cardRepository) {
        this(cardRepository, null, null);
    }

    public CancelCard(CardRepository cardRepository, VirtualCards virtualCards, CardPlastics plastics) {
        this.cardRepository = cardRepository;
        this.virtualCards = virtualCards;
        this.plastics = plastics;
    }

    @Transactional
    public Output execute(Input input) {
        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        card.cancel();
        Card saved = cardRepository.save(card);
        if (virtualCards != null) virtualCards.deleteAllOf(saved.getId());
        if (plastics != null) plastics.cancelAllOf(saved.getId());

        return new Output(saved.getId(), saved.getStatus().name());
    }
}
