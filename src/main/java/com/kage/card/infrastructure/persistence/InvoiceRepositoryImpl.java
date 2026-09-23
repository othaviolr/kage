package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.InvoiceRepository;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class InvoiceRepositoryImpl implements InvoiceRepository {

    private final InvoiceJpaRepository invoiceJpaRepository;
    private final InvoiceItemJpaRepository invoiceItemJpaRepository;

    public InvoiceRepositoryImpl(InvoiceJpaRepository invoiceJpaRepository,
                                 InvoiceItemJpaRepository invoiceItemJpaRepository) {
        this.invoiceJpaRepository = invoiceJpaRepository;
        this.invoiceItemJpaRepository = invoiceItemJpaRepository;
    }

    @Override
    public Invoice save(Invoice invoice) {
        InvoiceJpaEntity savedEntity = invoiceJpaRepository.save(InvoiceMapper.toJpaEntity(invoice));

        Set<UUID> alreadyPersistedIds = invoiceItemJpaRepository.findByInvoiceId(invoice.getId()).stream()
                .map(InvoiceItemJpaEntity::getId)
                .collect(Collectors.toSet());

        List<InvoiceItemJpaEntity> newItems = InvoiceMapper.toJpaItemEntities(invoice).stream()
                .filter(item -> !alreadyPersistedIds.contains(item.getId()))
                .toList();

        invoiceItemJpaRepository.saveAll(newItems);

        List<InvoiceItemJpaEntity> allItems = invoiceItemJpaRepository.findByInvoiceId(invoice.getId());
        return InvoiceMapper.toDomain(savedEntity, allItems);
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        return invoiceJpaRepository.findById(id)
                .map(entity -> InvoiceMapper.toDomain(entity, invoiceItemJpaRepository.findByInvoiceId(id)));
    }

    @Override
    public Optional<Invoice> findByCardIdAndReferenceMonth(UUID cardId, YearMonth referenceMonth) {
        return invoiceJpaRepository.findByCardIdAndReferenceMonth(cardId, referenceMonth.toString())
                .map(entity -> InvoiceMapper.toDomain(entity, invoiceItemJpaRepository.findByInvoiceId(entity.getId())));
    }
}
