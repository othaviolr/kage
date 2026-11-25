package com.kage.card.domain.repository;

import com.kage.card.domain.entity.Invoice;

import java.time.LocalDate;
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

    /** Faturas ainda abertas cujo dia de corte já passou (fechamento atrasado ou do dia). */
    List<InvoiceRef> findOpenWithClosingDateBefore(LocalDate date);

    /** Faturas fechadas, vencidas até {@code date}, de cartões com débito automático ligado. */
    List<InvoiceRef> findClosedDueUntilWithAutoDebit(LocalDate date);

    /** Faturas fechadas (não quitadas) com fechamento anterior a {@code date}. */
    List<InvoiceRef> findClosedWithClosingDateBefore(LocalDate date);

    record InvoiceRef(UUID cardId, YearMonth referenceMonth) {}
}
