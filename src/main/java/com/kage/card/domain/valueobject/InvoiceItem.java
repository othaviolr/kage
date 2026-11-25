package com.kage.card.domain.valueobject;

import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Linha da fatura. Compras e encargos do rotativo moram no mesmo lugar para o total ser sempre a
 * soma das linhas, e o {@code kind} diz o que é cada uma.
 *
 * @param purchaseId para compras, o id da compra (idempotência); para encargos, um id determinístico
 *                   derivado da fatura de origem — a mesma virada não lança o mesmo encargo duas vezes
 */
public record InvoiceItem(UUID id, UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt,
                          InvoiceItemKind kind) {

    public InvoiceItem {
        if (id == null) throw new ValidationException("Identificador do lançamento é obrigatório");
        if (purchaseId == null) throw new ValidationException("Identificador da compra é obrigatório");
        if (description == null || description.isBlank()) throw new ValidationException("Descrição do lançamento é obrigatória");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor do lançamento deve ser maior que zero");
        if (purchasedAt == null) throw new ValidationException("Data da compra é obrigatória");
        if (kind == null) throw new ValidationException("Tipo do lançamento é obrigatório");
    }

    public static InvoiceItem create(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {
        return new InvoiceItem(UUID.randomUUID(), purchaseId, description, amount, purchasedAt, InvoiceItemKind.PURCHASE);
    }

    public static InvoiceItem charge(InvoiceItemKind kind, UUID sourceInvoiceId, String description, Money amount,
                                     LocalDateTime at) {
        UUID deterministicId = UUID.nameUUIDFromBytes((kind.name() + ":" + sourceInvoiceId).getBytes());
        return new InvoiceItem(UUID.randomUUID(), deterministicId, description, amount, at, kind);
    }

    public boolean isPurchase() {
        return kind == InvoiceItemKind.PURCHASE;
    }
}
