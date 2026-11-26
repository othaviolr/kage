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
        entity.setApprovedLimit(card.getApprovedLimit().amount());
        entity.setUsedLimit(card.getUsedLimit().amount());
        entity.setClosingDay(card.getClosingDay());
        entity.setDueDay(card.getDueDay());
        entity.setStatus(card.getStatus());
        entity.setAutoDebit(card.isAutoDebit());
        entity.setRevolvingPrincipal(card.getRevolvingPrincipal().amount());
        entity.setRevolvingCharges(card.getRevolvingCharges().amount());
        entity.setCashbackDestination(card.getCashbackDestination());
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
                Money.of(entity.getApprovedLimit()),
                Money.of(entity.getUsedLimit()),
                entity.getClosingDay(),
                entity.getDueDay(),
                entity.getStatus(),
                entity.isAutoDebit(),
                Money.of(entity.getRevolvingPrincipal()),
                Money.of(entity.getRevolvingCharges()),
                entity.getCashbackDestination(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
