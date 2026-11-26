package com.kage.card.domain.repository;

import com.kage.card.domain.entity.VirtualCard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VirtualCardRepository {

    /**
     * UNIQUE (number_hash): dois virtuais nunca têm o mesmo número.
     *
     * @return false se o número já é de outro virtual (nada gravado; a transação segue viva)
     */
    boolean insert(VirtualCard card);

    /** Grava status e data de exclusão. */
    void update(VirtualCard card);

    Optional<VirtualCard> findById(UUID id);

    Optional<VirtualCard> findByNumberHash(String numberHash);

    /** Do cartão físico, apagados inclusive, do mais novo para o mais antigo. */
    List<VirtualCard> findByCardId(UUID cardId);
}
