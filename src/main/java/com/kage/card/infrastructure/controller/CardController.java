package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final IssueCard issueCard;
    private final GetCard getCard;
    private final BlockCard blockCard;
    private final UnblockCard unblockCard;
    private final RegisterPurchase registerPurchase;

    public CardController(IssueCard issueCard, GetCard getCard, BlockCard blockCard,
                          UnblockCard unblockCard, RegisterPurchase registerPurchase) {
        this.issueCard = issueCard;
        this.getCard = getCard;
        this.blockCard = blockCard;
        this.unblockCard = unblockCard;
        this.registerPurchase = registerPurchase;
    }

    @PostMapping
    public ResponseEntity<IssueCard.Output> issue(@RequestBody IssueCard.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issueCard.execute(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetCard.Output> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(getCard.execute(new GetCard.Input(id)));
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<BlockCard.Output> block(@PathVariable UUID id) {
        return ResponseEntity.ok(blockCard.execute(new BlockCard.Input(id)));
    }

    @PatchMapping("/{id}/unblock")
    public ResponseEntity<UnblockCard.Output> unblock(@PathVariable UUID id) {
        return ResponseEntity.ok(unblockCard.execute(new UnblockCard.Input(id)));
    }

    @PostMapping("/{id}/purchases")
    public ResponseEntity<RegisterPurchase.Output> registerPurchase(@PathVariable UUID id,
                                                                     @RequestBody PurchaseRequest request) {
        LocalDateTime purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : LocalDateTime.now();
        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                id, request.purchaseId(), request.description(), request.amount(), purchasedAt));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    public record PurchaseRequest(UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {
    }
}
