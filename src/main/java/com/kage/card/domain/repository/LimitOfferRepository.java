package com.kage.card.domain.repository;

import com.kage.card.domain.entity.LimitOffer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitOfferRepository {

    void insert(LimitOffer offer);

    /** Grava a resposta só se a oferta ainda está PENDING. @return false se outra resposta chegou antes */
    boolean respond(LimitOffer offer);

    Optional<LimitOffer> findById(UUID id);

    /** Do cartão, da mais nova para a mais antiga. */
    List<LimitOffer> findByCardId(UUID cardId);
}
