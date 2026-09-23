package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Card;
import com.kage.shared.domain.valueobject.Money;

public class CardMapper {

    public static CardJpaEntity toJpaEntity(Card card) {
        CardJpaEntity entity = new CardJpaEntity();
        entity.setId(card.getId());
        entity.setCustomerId(card.getCustomerId());
        entity.setAccountId(card.getAccountId());
        entity.setCreditLimit(card.getCreditLimit().amount());
        entity.setUsedLimit(card.getUsedLimit().amount());
        entity.setClosingDay(card.getClosingDay());
        entity.setDueDay(card.getDueDay());
        entity.setStatus(card.getStatus());
        entity.setCreatedAt(card.getCreatedAt());
        entity.setUpdatedAt(card.getUpdatedAt());
        entity.setVersion(card.getVersion());
        return entity;
    }

    public static Card toDomain(CardJpaEntity entity) {
        return Card.reconstitute(
                entity.getId(),
                entity.getCustomerId(),
                entity.getAccountId(),
                Money.of(entity.getCreditLimit()),
                Money.of(entity.getUsedLimit()),
                entity.getClosingDay(),
                entity.getDueDay(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
