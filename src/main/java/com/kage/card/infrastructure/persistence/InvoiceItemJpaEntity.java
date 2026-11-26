package com.kage.card.infrastructure.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invoice_items")
public class InvoiceItemJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "purchase_id", nullable = false, updatable = false)
    private UUID purchaseId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 32)
    private com.kage.card.domain.enums.InvoiceItemKind kind;

    @Column(name = "virtual_card_id", updatable = false)
    private UUID virtualCardId;

    public InvoiceItemJpaEntity() {}

    public UUID getVirtualCardId() { return virtualCardId; }
    public void setVirtualCardId(UUID virtualCardId) { this.virtualCardId = virtualCardId; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
    public UUID getPurchaseId() { return purchaseId; }
    public void setPurchaseId(UUID purchaseId) { this.purchaseId = purchaseId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDateTime getPurchasedAt() { return purchasedAt; }
    public void setPurchasedAt(LocalDateTime purchasedAt) { this.purchasedAt = purchasedAt; }
    @Column(name = "installment_number", updatable = false)
    private Integer installmentNumber;

    @Column(name = "installment_count", updatable = false)
    private Integer installmentCount;

    public Integer getInstallmentNumber() { return installmentNumber; }
    public void setInstallmentNumber(Integer installmentNumber) { this.installmentNumber = installmentNumber; }
    public Integer getInstallmentCount() { return installmentCount; }
    public void setInstallmentCount(Integer installmentCount) { this.installmentCount = installmentCount; }
    public com.kage.card.domain.enums.InvoiceItemKind getKind() { return kind; }
    public void setKind(com.kage.card.domain.enums.InvoiceItemKind kind) { this.kind = kind; }
}
