package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.InvoiceCharges;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Pagar a fatura de outro banco: boleto ou PIX copia e cola gerado para a fatura fechada. */
@RestController
public class InvoiceChargeController {

    private final InvoiceCharges invoiceCharges;
    private final AccessGuard accessGuard;

    public InvoiceChargeController(InvoiceCharges invoiceCharges, AccessGuard accessGuard) {
        this.invoiceCharges = invoiceCharges;
        this.accessGuard = accessGuard;
    }

    /** @param amount nulo = o saldo devedor inteiro */
    public record IssueRequest(String method, BigDecimal amount) {
    }

    @PostMapping("/api/cards/{id}/invoices/{referenceMonth}/charges")
    public ResponseEntity<InvoiceCharges.ChargeView> issue(@PathVariable UUID id, @PathVariable String referenceMonth,
                                                           @RequestBody(required = false) IssueRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceCharges.issue(id, referenceMonth,
                request == null ? null : request.method(), request == null ? null : request.amount()));
    }

    @GetMapping("/api/cards/{id}/invoices/{referenceMonth}/charges")
    public ResponseEntity<List<InvoiceCharges.ChargeView>> list(@PathVariable UUID id, @PathVariable String referenceMonth) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(invoiceCharges.list(id, referenceMonth));
    }

    @DeleteMapping("/api/cards/{id}/invoice-charges/{chargeId}")
    public ResponseEntity<InvoiceCharges.ChargeView> cancel(@PathVariable UUID id, @PathVariable UUID chargeId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(invoiceCharges.cancel(id, chargeId));
    }
}
