package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.InvoiceCharges;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Backoffice: o dinheiro de uma cobrança de fatura chegou de outro banco. Em produção, quem chama é o
 * arquivo de retorno da compensação (boleto) ou a notificação do SPI (PIX); aqui, simula.
 */
@RestController
@RequestMapping("/api/admin/invoice-charges")
public class InvoiceChargeAdminController {

    private final InvoiceCharges invoiceCharges;

    public InvoiceChargeAdminController(InvoiceCharges invoiceCharges) {
        this.invoiceCharges = invoiceCharges;
    }

    /** @param paidAt quando o cliente pagou lá (o boleto compensa depois); nulo = agora */
    public record SettleRequest(LocalDateTime paidAt) {
    }

    @PostMapping("/{chargeId}/settle")
    public ResponseEntity<InvoiceCharges.Settlement> settle(@PathVariable UUID chargeId,
                                                           @RequestBody(required = false) SettleRequest request) {
        return ResponseEntity.ok(invoiceCharges.settle(chargeId, request == null ? null : request.paidAt()));
    }
}
