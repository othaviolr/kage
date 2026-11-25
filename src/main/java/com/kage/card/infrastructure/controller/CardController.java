package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.*;
import com.kage.auth.infrastructure.security.AccessGuard;
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
    private final GetInvoice getInvoice;
    private final ListInvoices listInvoices;
    private final CloseInvoice closeInvoice;
    private final CancelCard cancelCard;
    private final AccessGuard accessGuard;
    private final PayInvoice payInvoice;

    /**
     * Emissão é do ADMIN: o limite de crédito é decisão da análise de crédito do banco, não do cliente.
     * Compras chegam pela bandeira e o fechamento é job do banco (ambos ADMIN no SecurityConfig).
     * O cliente consulta, bloqueia/desbloqueia, cancela e vê as faturas dos próprios cartões.
     */
    public CardController(IssueCard issueCard, GetCard getCard, BlockCard blockCard,
                          UnblockCard unblockCard, RegisterPurchase registerPurchase,
                          GetInvoice getInvoice, ListInvoices listInvoices,
                          CloseInvoice closeInvoice, CancelCard cancelCard, AccessGuard accessGuard,
                          PayInvoice payInvoice) {
        this.accessGuard = accessGuard;
        this.payInvoice = payInvoice;
        this.issueCard = issueCard;
        this.getCard = getCard;
        this.blockCard = blockCard;
        this.unblockCard = unblockCard;
        this.registerPurchase = registerPurchase;
        this.getInvoice = getInvoice;
        this.listInvoices = listInvoices;
        this.closeInvoice = closeInvoice;
        this.cancelCard = cancelCard;
    }

    @PostMapping
    public ResponseEntity<IssueCard.Output> issue(@RequestBody IssueCard.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issueCard.execute(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetCard.Output> getById(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(getCard.execute(new GetCard.Input(id)));
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<BlockCard.Output> block(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(blockCard.execute(new BlockCard.Input(id)));
    }

    @PatchMapping("/{id}/unblock")
    public ResponseEntity<UnblockCard.Output> unblock(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(unblockCard.execute(new UnblockCard.Input(id)));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<CancelCard.Output> cancel(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(cancelCard.execute(new CancelCard.Input(id)));
    }

    @PostMapping("/{id}/purchases")
    public ResponseEntity<RegisterPurchase.Output> registerPurchase(@PathVariable UUID id,
                                                                     @RequestBody PurchaseRequest request) {
        LocalDateTime purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : LocalDateTime.now();
        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                id, request.purchaseId(), request.description(), request.amount(), purchasedAt));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    @GetMapping("/{id}/invoices")
    public ResponseEntity<ListInvoices.Output> listInvoices(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(listInvoices.execute(new ListInvoices.Input(id)));
    }

    @GetMapping("/{id}/invoices/{referenceMonth}")
    public ResponseEntity<GetInvoice.Output> getInvoice(@PathVariable UUID id, @PathVariable String referenceMonth) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(getInvoice.execute(new GetInvoice.Input(id, referenceMonth)));
    }

    /** Paga o total da fatura fechada com o saldo da conta vinculada ao cartão. */
    @PostMapping("/{id}/invoices/{referenceMonth}/pay")
    public ResponseEntity<PayInvoice.Output> payInvoice(@PathVariable UUID id, @PathVariable String referenceMonth) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(payInvoice.execute(new PayInvoice.Input(id, referenceMonth)));
    }

    @PatchMapping("/{id}/invoices/{referenceMonth}/close")
    public ResponseEntity<CloseInvoice.Output> closeInvoice(@PathVariable UUID id, @PathVariable String referenceMonth) {
        return ResponseEntity.ok(closeInvoice.execute(new CloseInvoice.Input(id, referenceMonth)));
    }

    public record PurchaseRequest(UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {
    }
}
