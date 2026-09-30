package com.kage.card.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceJpaRepository extends JpaRepository<InvoiceJpaEntity, UUID> {

    Optional<InvoiceJpaEntity> findByCardIdAndReferenceMonth(UUID cardId, String referenceMonth);

    List<InvoiceJpaEntity> findByCardIdOrderByReferenceMonthDesc(UUID cardId);
}
