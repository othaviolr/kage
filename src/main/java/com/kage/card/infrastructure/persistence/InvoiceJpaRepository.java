package com.kage.card.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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

    @Query("""
            SELECT i FROM InvoiceJpaEntity i
            WHERE i.status = com.kage.card.domain.enums.InvoiceStatus.OPEN AND i.closingDate < :date
            ORDER BY i.closingDate
            """)
    List<InvoiceJpaEntity> findOpenWithClosingDateBefore(@Param("date") LocalDate date);

    @Query("""
            SELECT i FROM InvoiceJpaEntity i
            WHERE i.status = com.kage.card.domain.enums.InvoiceStatus.CLOSED AND i.closingDate < :date
            ORDER BY i.closingDate
            """)
    List<InvoiceJpaEntity> findClosedWithClosingDateBefore(@Param("date") LocalDate date);

    @Query("""
            SELECT i FROM InvoiceJpaEntity i JOIN CardJpaEntity c ON c.id = i.cardId
            WHERE i.status = com.kage.card.domain.enums.InvoiceStatus.CLOSED
              AND i.dueDate <= :date
              AND c.autoDebit = true
            ORDER BY i.dueDate
            """)
    List<InvoiceJpaEntity> findClosedDueUntilWithAutoDebit(@Param("date") LocalDate date);
}
