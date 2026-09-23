package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

public class GetCard {

    public record Input(UUID cardId) {}
    public record Output(UUID cardId, UUID customerId, UUID accountId, BigDecimal creditLimit,
                         BigDecimal usedLimit, BigDecimal availableLimit, int closingDay, int dueDay, String status) {}

    private final CardRepository cardRepository;

    public GetCard(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public Output execute(Input input) {
        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        return new Output(card.getId(), card.getCustomerId(), card.getAccountId(), card.getCreditLimit().amount(),
                card.getUsedLimit().amount(), card.availableLimit().amount(), card.getClosingDay(),
                card.getDueDay(), card.getStatus().name());
    }
}
