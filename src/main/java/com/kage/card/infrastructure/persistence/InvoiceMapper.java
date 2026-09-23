package com.kage.card.infrastructure.persistence;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.valueobject.Money;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

public class InvoiceMapper {

    public static InvoiceJpaEntity toJpaEntity(Invoice invoice) {
        InvoiceJpaEntity entity = new InvoiceJpaEntity();
        entity.setId(invoice.getId());
        entity.setCardId(invoice.getCardId());
        entity.setReferenceMonth(invoice.getReferenceMonth().toString());
        entity.setClosingDate(invoice.getClosingDate());
        entity.setDueDate(invoice.getDueDate());
        entity.setStatus(invoice.getStatus());
        entity.setPaidAt(invoice.getPaidAt());
        entity.setCreatedAt(invoice.getCreatedAt());
        entity.setUpdatedAt(invoice.getUpdatedAt());
        entity.setVersion(invoice.getVersion());
        return entity;
    }

    public static List<InvoiceItemJpaEntity> toJpaItemEntities(Invoice invoice) {
        return invoice.getItems().stream().map(item -> {
            InvoiceItemJpaEntity entity = new InvoiceItemJpaEntity();
            entity.setId(item.id());
            entity.setInvoiceId(invoice.getId());
            entity.setPurchaseId(item.purchaseId());
            entity.setDescription(item.description());
            entity.setAmount(item.amount().amount());
            entity.setPurchasedAt(item.purchasedAt());
            return entity;
        }).collect(Collectors.toList());
    }

    public static Invoice toDomain(InvoiceJpaEntity entity, List<InvoiceItemJpaEntity> itemEntities) {
        List<InvoiceItem> items = itemEntities.stream()
                .map(item -> new InvoiceItem(item.getId(), item.getPurchaseId(), item.getDescription(),
                        Money.of(item.getAmount()), item.getPurchasedAt()))
                .collect(Collectors.toList());

        return Invoice.reconstitute(
                entity.getId(),
                entity.getCardId(),
                YearMonth.parse(entity.getReferenceMonth()),
                entity.getClosingDate(),
                entity.getDueDate(),
                entity.getStatus(),
                items,
                entity.getPaidAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
