package com.kage.card.domain.repository;

import com.kage.card.domain.entity.Card;

import java.util.Optional;
import java.util.UUID;

public interface CardRepository {

    Card save(Card card);
    Optional<Card> findById(UUID id);
}
