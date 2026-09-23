package com.kage.card.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InvoiceItemJpaRepository extends JpaRepository<InvoiceItemJpaEntity, UUID> {

    List<InvoiceItemJpaEntity> findByInvoiceId(UUID invoiceId);
}
