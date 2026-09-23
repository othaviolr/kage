package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;

import java.util.Optional;
import java.util.UUID;

public class CardRepositoryImpl implements CardRepository {

    private final CardJpaRepository cardJpaRepository;

    public CardRepositoryImpl(CardJpaRepository cardJpaRepository) {
        this.cardJpaRepository = cardJpaRepository;
    }

    @Override
    public Card save(Card card) {
        CardJpaEntity entity = CardMapper.toJpaEntity(card);
        CardJpaEntity saved = cardJpaRepository.save(entity);
        return CardMapper.toDomain(saved);
    }

    @Override
    public Optional<Card> findById(UUID id) {
        return cardJpaRepository.findById(id).map(CardMapper::toDomain);
    }
}
