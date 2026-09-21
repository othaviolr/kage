package com.kage.card.domain.entity;

import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Invoice {

    private final UUID id;
    private final UUID cardId;
    private final YearMonth referenceMonth;
    private final LocalDate closingDate;
    private final LocalDate dueDate;
    private InvoiceStatus status;
    private final List<InvoiceItem> items;
    private LocalDateTime paidAt;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private final Long version;

    private Invoice(UUID id, UUID cardId, YearMonth referenceMonth, LocalDate closingDate,
                    LocalDate dueDate, InvoiceStatus status, List<InvoiceItem> items,
                    LocalDateTime paidAt, LocalDateTime createdAt, LocalDateTime updatedAt, Long version) {
        this.id = id;
        this.cardId = cardId;
        this.referenceMonth = referenceMonth;
        this.closingDate = closingDate;
        this.dueDate = dueDate;
        this.status = status;
        this.items = new ArrayList<>(items);
        this.paidAt = paidAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Invoice open(UUID cardId, YearMonth referenceMonth, int closingDay, int dueDay) {
        if (cardId == null) throw new ValidationException("Cartão é obrigatório");
        if (referenceMonth == null) throw new ValidationException("Mês de referência é obrigatório");

        LocalDate closingDate = referenceMonth.atDay(closingDay);
        LocalDate dueDate = dueDay > closingDay
                ? referenceMonth.atDay(dueDay)
                : referenceMonth.plusMonths(1).atDay(dueDay);

        LocalDateTime now = LocalDateTime.now();
        return new Invoice(UUID.randomUUID(), cardId, referenceMonth, closingDate, dueDate,
                InvoiceStatus.OPEN, List.of(), null, now, now, null);
    }

    public static Invoice reconstitute(UUID id, UUID cardId, YearMonth referenceMonth, LocalDate closingDate,
                                       LocalDate dueDate, InvoiceStatus status, List<InvoiceItem> items,
                                       LocalDateTime paidAt, LocalDateTime createdAt, LocalDateTime updatedAt,
                                       Long version) {
        return new Invoice(id, cardId, referenceMonth, closingDate, dueDate, status, items,
                paidAt, createdAt, updatedAt, version);
    }

    public static YearMonth referenceMonthFor(LocalDate purchaseDate, int closingDay) {
        YearMonth month = YearMonth.from(purchaseDate);
        return purchaseDate.getDayOfMonth() <= closingDay ? month : month.plusMonths(1);
    }

    public void addItem(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Fatura não está aberta para novos lançamentos");
        boolean alreadyRegistered = items.stream().anyMatch(item -> item.purchaseId().equals(purchaseId));
        if (alreadyRegistered) throw new ConflictException("Compra já lançada nesta fatura");

        this.items.add(InvoiceItem.create(purchaseId, description, amount, purchasedAt));
        this.updatedAt = LocalDateTime.now();
    }

    public Money total() {
        return items.stream()
                .map(InvoiceItem::amount)
                .reduce(Money.ZERO, Money::add);
    }

    public void close() {
        if (this.status != InvoiceStatus.OPEN) throw new BusinessRuleException("Somente faturas abertas podem ser fechadas");
        this.status = InvoiceStatus.CLOSED;
        this.updatedAt = LocalDateTime.now();
    }

    public void pay() {
        if (this.status != InvoiceStatus.CLOSED) throw new BusinessRuleException("Somente faturas fechadas podem ser pagas");
        this.status = InvoiceStatus.PAID;
        this.paidAt = LocalDateTime.now();
        this.updatedAt = this.paidAt;
    }

    public boolean isOverdue(LocalDate today) {
        return this.status == InvoiceStatus.CLOSED && today.isAfter(this.dueDate);
    }

    public UUID getId() { return id; }
    public UUID getCardId() { return cardId; }
    public YearMonth getReferenceMonth() { return referenceMonth; }
    public LocalDate getClosingDate() { return closingDate; }
    public LocalDate getDueDate() { return dueDate; }
    public InvoiceStatus getStatus() { return status; }
    public List<InvoiceItem> getItems() { return Collections.unmodifiableList(items); }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
