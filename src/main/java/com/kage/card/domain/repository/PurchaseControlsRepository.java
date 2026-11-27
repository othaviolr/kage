package com.kage.card.domain.repository;

import com.kage.card.domain.valueobject.PurchaseControls;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseControlsRepository {

    /** Vazio = o cliente nunca mexeu: tudo ligado. */
    Optional<PurchaseControls> find(UUID cardId);

    void save(UUID cardId, PurchaseControls controls, LocalDateTime updatedAt);
}
