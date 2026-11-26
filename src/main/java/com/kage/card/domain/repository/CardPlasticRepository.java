package com.kage.card.domain.repository;

import com.kage.card.domain.entity.CardPlastic;

import java.util.List;
import java.util.UUID;

public interface CardPlasticRepository {

    /**
     * UNIQUE: número, (cartão, sequência), um ACTIVE e um PENDING_ACTIVATION por cartão.
     *
     * @return false se o número já existe (nada gravado; sortear outro id)
     */
    boolean insert(CardPlastic plastic);

    /** Grava status e datas. */
    void update(CardPlastic plastic);

    /** Do cartão, da via mais nova para a mais antiga. */
    List<CardPlastic> findByCardId(UUID cardId);
}
