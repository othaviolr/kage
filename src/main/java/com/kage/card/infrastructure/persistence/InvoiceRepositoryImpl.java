package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.valueobject.InvoiceItem;

import java.time.LocalDate;
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
        Set<UUID> currentIds = invoice.getItems().stream().map(InvoiceItem::id).collect(Collectors.toSet());

        List<InvoiceItemJpaEntity> newItems = InvoiceMapper.toJpaItemEntities(invoice).stream()
                .filter(item -> !alreadyPersistedIds.contains(item.getId()))
                .toList();

        // linha que saiu da fatura (parcela antecipada). Seguro contra corrida: a versão da fatura
        // acabou de ser conferida no save acima
        List<UUID> removed = alreadyPersistedIds.stream().filter(id -> !currentIds.contains(id)).toList();
        if (!removed.isEmpty()) invoiceItemJpaRepository.deleteAllByIdInBatch(removed);

        invoiceItemJpaRepository.saveAll(newItems);

        List<InvoiceItemJpaEntity> allItems = invoiceItemJpaRepository.findByInvoiceId(invoice.getId());
        return InvoiceMapper.toDomain(savedEntity, allItems);
    }

    @Override
    public void delete(Invoice invoice) {
        if (!invoice.isEmpty()) throw new IllegalStateException("Só fatura sem lançamentos pode ser removida");
        invoiceItemJpaRepository.deleteAllByIdInBatch(invoiceItemJpaRepository.findByInvoiceId(invoice.getId()).stream()
                .map(InvoiceItemJpaEntity::getId).toList());
        invoiceJpaRepository.delete(InvoiceMapper.toJpaEntity(invoice)); // com a versão: conflito vira erro
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

    @Override
    public List<Invoice> findByCardId(UUID cardId) {
        return invoiceJpaRepository.findByCardIdOrderByReferenceMonthDesc(cardId).stream()
                .map(entity -> InvoiceMapper.toDomain(entity, invoiceItemJpaRepository.findByInvoiceId(entity.getId())))
                .toList();
    }

    @Override
    public List<InvoiceRef> findOpenWithClosingDateBefore(LocalDate date) {
        return invoiceJpaRepository.findOpenWithClosingDateBefore(date).stream().map(InvoiceRepositoryImpl::toRef).toList();
    }

    @Override
    public List<InvoiceRef> findClosedDueUntilWithAutoDebit(LocalDate date) {
        return invoiceJpaRepository.findClosedDueUntilWithAutoDebit(date).stream().map(InvoiceRepositoryImpl::toRef).toList();
    }

    @Override
    public List<InvoiceRef> findClosedWithClosingDateBefore(LocalDate date) {
        return invoiceJpaRepository.findClosedWithClosingDateBefore(date).stream().map(InvoiceRepositoryImpl::toRef).toList();
    }

    private static InvoiceRef toRef(InvoiceJpaEntity entity) {
        return new InvoiceRef(entity.getCardId(), YearMonth.parse(entity.getReferenceMonth()));
    }

    @Override
    public Optional<Invoice> findByCardIdAndPurchaseId(UUID cardId, UUID purchaseId) {
        return invoiceJpaRepository.findByCardIdAndPurchaseId(cardId, purchaseId)
                .map(entity -> InvoiceMapper.toDomain(entity, invoiceItemJpaRepository.findByInvoiceId(entity.getId())));
    }
}
