package com.kage.card.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<InvoiceJpaEntity, UUID> {

    Optional<InvoiceJpaEntity> findByCardIdAndReferenceMonth(UUID cardId, String referenceMonth);

    List<InvoiceJpaEntity> findByCardIdOrderByReferenceMonthDesc(UUID cardId);

    @Query(value = """
            SELECT i.* FROM invoices i
            JOIN invoice_items it ON it.invoice_id = i.id
            WHERE i.card_id = :cardId AND it.purchase_id = :purchaseId
            """, nativeQuery = true)
    Optional<InvoiceJpaEntity> findByCardIdAndPurchaseId(@Param("cardId") UUID cardId,
                                                          @Param("purchaseId") UUID purchaseId);
}
