package com.kage.card.domain.repository;

import com.kage.card.domain.entity.Dispute;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository {

    /** UNIQUE (card_id, purchase_id): uma compra só é contestada uma vez. */
    void insert(Dispute dispute);

    /**
     * Grava a resolução só se a contestação ainda está aberta.
     *
     * @return false se outra resolução chegou antes
     */
    boolean resolve(Dispute resolved);

    Optional<Dispute> findById(UUID id);

    Optional<Dispute> findByCardIdAndPurchaseId(UUID cardId, UUID purchaseId);

    /** Da mais nova para a mais antiga. */
    List<Dispute> findByCardId(UUID cardId);
}
