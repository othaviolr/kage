package com.kage.card.domain.repository;

import com.kage.card.domain.entity.Invoice;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository {

    Invoice save(Invoice invoice);
    Optional<Invoice> findById(UUID id);
    Optional<Invoice> findByCardIdAndReferenceMonth(UUID cardId, YearMonth referenceMonth);
    List<Invoice> findByCardId(UUID cardId);
    Optional<Invoice> findByCardIdAndPurchaseId(UUID cardId, UUID purchaseId);
}
