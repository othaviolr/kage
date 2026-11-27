package com.kage.card.domain.repository;

import com.kage.card.domain.service.InternationalPurchasePolicy;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** A conversão de cada compra internacional, como foi feita no dia (cotação, spread, IOF). */
public interface InternationalPurchaseRepository {

    void insert(UUID cardId, UUID purchaseId, InternationalPurchasePolicy.Quote quote, LocalDateTime purchasedAt);

    Optional<InternationalPurchasePolicy.Quote> find(UUID purchaseId);
}
