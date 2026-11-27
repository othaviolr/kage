package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.PurchaseCategories;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Categoria das compras: a lista de categorias, o resumo de gastos por categoria numa fatura e a
 * categoria de uma compra (que o cliente pode trocar).
 */
@RestController
public class PurchaseCategoryController {

    private final PurchaseCategories purchaseCategories;
    private final AccessGuard accessGuard;

    public PurchaseCategoryController(PurchaseCategories purchaseCategories, AccessGuard accessGuard) {
        this.purchaseCategories = purchaseCategories;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/cards/purchase-categories")
    public ResponseEntity<List<PurchaseCategories.Category>> catalog() {
        return ResponseEntity.ok(PurchaseCategories.catalog());
    }

    /** @param month "yyyy-MM"; sem ele, a fatura atual */
    @GetMapping("/api/cards/{id}/spending")
    public ResponseEntity<PurchaseCategories.SpendingOutput> spending(@PathVariable UUID id,
                                                                      @RequestParam(required = false) String month) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(purchaseCategories.spending(id, month));
    }

    @GetMapping("/api/cards/{id}/purchases/{purchaseId}/category")
    public ResponseEntity<PurchaseCategories.PurchaseOutput> get(@PathVariable UUID id, @PathVariable UUID purchaseId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(purchaseCategories.get(id, purchaseId));
    }

    @PatchMapping("/api/cards/{id}/purchases/{purchaseId}/category")
    public ResponseEntity<PurchaseCategories.PurchaseOutput> customize(@PathVariable UUID id, @PathVariable UUID purchaseId,
                                                                       @RequestBody CategoryRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(purchaseCategories.customize(id, purchaseId, request.category()));
    }

    public record CategoryRequest(String category) {
    }
}
