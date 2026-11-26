package com.kage.card.domain.service;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

/**
 * Cashback de {@link #RATE} sobre as compras de uma fatura paga inteira até o vencimento. Juros, IOF e
 * encargos não contam (não são compra), e compra contestada fica de fora. Fatura paga com atraso, que
 * foi para o rotativo ou foi parcelada não dá cashback. Arredonda para baixo: o banco não paga fração
 * de centavo.
 */
public final class CashbackPolicy {

    public static final BigDecimal RATE = new BigDecimal("0.01");

    private CashbackPolicy() {
    }

    public static boolean eligible(Invoice invoice) {
        return invoice.getStatus() == InvoiceStatus.PAID && invoice.getPaidAt() != null
                && !invoice.getPaidAt().toLocalDate().isAfter(invoice.getDueDate());
    }

    /** Base do cashback: as linhas de compra da fatura, menos as das compras contestadas. */
    public static Money base(Invoice invoice, Set<UUID> disputedPurchases) {
        return invoice.getItems().stream()
                .filter(InvoiceItem::isPurchase)
                .filter(item -> !disputedPurchases.contains(item.purchaseId()))
                .map(InvoiceItem::amount)
                .reduce(Money.ZERO, Money::add);
    }

    public static Money of(Money base) {
        return Money.of(base.amount().multiply(RATE).setScale(2, RoundingMode.DOWN));
    }
}
