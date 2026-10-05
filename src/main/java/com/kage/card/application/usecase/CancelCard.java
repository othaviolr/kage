package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

public class CancelCard {

    public record Input(UUID cardId) {}
    public record Output(UUID cardId, String status) {}

    private final CardRepository cardRepository;

    public CancelCard(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public Output execute(Input input) {
        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        card.cancel();
        Card saved = cardRepository.save(card);

        return new Output(saved.getId(), saved.getStatus().name());
    }
}
