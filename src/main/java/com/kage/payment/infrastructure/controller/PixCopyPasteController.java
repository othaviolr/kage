package com.kage.payment.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.payment.application.usecase.PixCopyPaste;
import com.kage.payment.domain.repository.PixBillingPaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * PIX copia e cola: ler (qualquer cliente logado, como a consulta de boleto) e pagar (só da própria
 * conta). O código vai no corpo, e não na URL: é longo e tem espaços.
 */
@RestController
@RequestMapping("/api/pix/copy-paste")
public class PixCopyPasteController {

    private final PixCopyPaste pixCopyPaste;
    private final AccessGuard accessGuard;

    public PixCopyPasteController(PixCopyPaste pixCopyPaste, AccessGuard accessGuard) {
        this.pixCopyPaste = pixCopyPaste;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/read")
    public ResponseEntity<PixCopyPaste.Preview> read(@RequestBody ReadRequest request) {
        return ResponseEntity.ok(pixCopyPaste.read(request.code()));
    }

    @PostMapping("/pay")
    public ResponseEntity<PixCopyPaste.Paid> pay(@RequestBody PayRequest request) {
        accessGuard.checkAccount(request.sourceAccountId()); // só sai dinheiro da própria conta
        return ResponseEntity.status(HttpStatus.CREATED).body(pixCopyPaste.pay(request.sourceAccountId(), request.code(),
                request.amount(), request.description()));
    }

    /** Cobrança do Kage paga pelo copia e cola: só quem pagou consulta. */
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<PixBillingPaymentRepository.Payment> payment(@PathVariable UUID paymentId) {
        PixBillingPaymentRepository.Payment payment = pixCopyPaste.payment(paymentId);
        accessGuard.checkAccount(payment.accountId());
        return ResponseEntity.ok(payment);
    }

    public record ReadRequest(String code) {}

    public record PayRequest(UUID sourceAccountId, String code, BigDecimal amount, String description) {}
}
