package com.kage.card.domain.repository;

import com.kage.card.domain.entity.Card;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository {

    Card save(Card card);
    Optional<Card> findById(UUID id);

    /** Ids dos cartões ativos (para a análise de limite do banco). */
    List<UUID> findActiveIds();
}
