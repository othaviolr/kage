package com.kage.card.domain.repository;

import com.kage.card.domain.enums.PurchaseCategory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A categoria de cada compra (do MCC, ou a que o cliente escolheu). Sem registro = "Outros". */
public interface PurchaseCategoryRepository {

    /**
     * @param mcc      nulo = compra sem MCC
     * @param customized categoria escolhida pelo cliente (não a do MCC)
     */
    record Entry(UUID purchaseId, String mcc, PurchaseCategory category, boolean customized) {}

    /** Na autorização; a mesma compra de novo não muda nada. */
    void insert(UUID cardId, UUID purchaseId, String mcc, PurchaseCategory category, LocalDateTime at);

    /** O cliente troca a categoria (mantém o MCC). */
    void customize(UUID cardId, UUID purchaseId, PurchaseCategory category, LocalDateTime at);

    Optional<Entry> find(UUID cardId, UUID purchaseId);

    Map<UUID, Entry> findByCardId(UUID cardId);
}
