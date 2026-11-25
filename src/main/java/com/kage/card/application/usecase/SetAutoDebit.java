package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;

import java.util.UUID;

public class SetAutoDebit {

    public record Input(UUID cardId, Boolean enabled) {}
    public record Output(UUID cardId, boolean autoDebit) {}

    private final CardRepository cardRepository;

    public SetAutoDebit(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public Output execute(Input input) {
        if (input.enabled() == null) {
            throw new ValidationException("Informe se o débito automático fica ligado (enabled)");
        }
        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        if (input.enabled()) card.enableAutoDebit();
        else card.disableAutoDebit();

        Card saved = cardRepository.save(card);
        return new Output(saved.getId(), saved.isAutoDebit());
    }
}
