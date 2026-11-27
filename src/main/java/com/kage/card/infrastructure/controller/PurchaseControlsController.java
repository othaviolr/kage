package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.CardPurchaseControls;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Liga e desliga compras online (cartão físico), internacionais e por aproximação. */
@RestController
@RequestMapping("/api/cards/{id}/purchase-controls")
public class PurchaseControlsController {

    private final CardPurchaseControls purchaseControls;
    private final AccessGuard accessGuard;

    public PurchaseControlsController(CardPurchaseControls purchaseControls, AccessGuard accessGuard) {
        this.purchaseControls = purchaseControls;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<CardPurchaseControls.View> get(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(purchaseControls.get(id));
    }

    /** Campo ausente = não muda. */
    @PatchMapping
    public ResponseEntity<CardPurchaseControls.View> update(@PathVariable UUID id,
                                                            @RequestBody(required = false) UpdateRequest request) {
        accessGuard.checkCard(id);
        UpdateRequest r = request == null ? new UpdateRequest(null, null, null) : request;
        return ResponseEntity.ok(purchaseControls.update(id, r.online(), r.international(), r.contactless()));
    }

    public record UpdateRequest(Boolean online, Boolean international, Boolean contactless) {
    }
}
