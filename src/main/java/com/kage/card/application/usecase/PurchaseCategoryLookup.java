package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.PurchaseCategoryRepository;
import com.kage.card.domain.valueobject.InvoiceItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A categoria de cada linha de fatura de um cartão. Têm categoria as linhas que são gasto: a compra (cada
 * parcela), a compra que voltou numa contestação negada e o crédito da contestação (que abate o gasto
 * da categoria da compra). Juros, IOF, saldos e cashback não têm.
 */
final class PurchaseCategoryLookup {

    private final Map<UUID, PurchaseCategoryRepository.Entry> categories;
    /** purchaseId da linha da contestação (crédito ou compra de volta) → a compra contestada. */
    private final Map<UUID, UUID> disputedPurchaseOf = new HashMap<>();

    private PurchaseCategoryLookup(UUID cardId, PurchaseCategoryRepository categories, DisputeRepository disputes) {
        this.categories = categories.findByCardId(cardId);
        for (Dispute d : disputes.findByCardId(cardId)) {
            disputedPurchaseOf.put(InvoiceItem.chargeId(InvoiceItemKind.DISPUTE_REVERSAL, d.id()), d.purchaseId());
            disputedPurchaseOf.put(InvoiceItem.chargeId(InvoiceItemKind.DISPUTE_CREDIT,
                    DisputePurchase.creditKey(cardId, d.purchaseId())), d.purchaseId());
        }
    }

    static PurchaseCategoryLookup of(UUID cardId, PurchaseCategoryRepository categories, DisputeRepository disputes) {
        return new PurchaseCategoryLookup(cardId, categories, disputes);
    }

    /** Nulo = a linha não é gasto (encargo, saldo, crédito que não é de compra). */
    PurchaseCategory categoryOf(InvoiceItem item) {
        UUID purchase = switch (item.kind()) {
            case PURCHASE -> item.purchaseId();
            case DISPUTE_REVERSAL, DISPUTE_CREDIT -> disputedPurchaseOf.get(item.purchaseId());
            default -> null;
        };
        if (purchase == null) return null;
        PurchaseCategoryRepository.Entry entry = categories.get(purchase);
        return entry == null ? PurchaseCategory.OTHER : entry.category();
    }
}
