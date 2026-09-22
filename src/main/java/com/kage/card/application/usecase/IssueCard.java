package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.util.UUID;

public class IssueCard {

    public record Input(UUID customerId, UUID accountId, BigDecimal creditLimit, int closingDay, int dueDay) {}
    public record Output(UUID cardId, String status, BigDecimal creditLimit, BigDecimal availableLimit, int closingDay, int dueDay) {}

    private final CardRepository cardRepository;

    public IssueCard(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public Output execute(Input input) {
        if (input.creditLimit() == null || input.creditLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Limite de crédito deve ser maior que zero");
        }

        Card card = Card.create(input.customerId(), input.accountId(), new Money(input.creditLimit()),
                input.closingDay(), input.dueDay());
        Card saved = cardRepository.save(card);

        return new Output(saved.getId(), saved.getStatus().name(), saved.getCreditLimit().amount(),
                saved.availableLimit().amount(), saved.getClosingDay(), saved.getDueDay());
    }
}
