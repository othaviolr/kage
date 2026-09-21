package com.kage.card.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

public record InvoiceItem(UUID id, UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {

    public InvoiceItem {
        if (id == null) throw new ValidationException("Identificador do lançamento é obrigatório");
        if (purchaseId == null) throw new ValidationException("Identificador da compra é obrigatório");
        if (description == null || description.isBlank()) throw new ValidationException("Descrição do lançamento é obrigatória");
        if (amount == null || amount.isZero()) throw new ValidationException("Valor do lançamento deve ser maior que zero");
        if (purchasedAt == null) throw new ValidationException("Data da compra é obrigatória");
    }

    public static InvoiceItem create(UUID purchaseId, String description, Money amount, LocalDateTime purchasedAt) {
        return new InvoiceItem(UUID.randomUUID(), purchaseId, description, amount, purchasedAt);
    }
}
