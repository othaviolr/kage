package com.kage.payment.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.payment.application.usecase.*;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Chaves e envio: só a partir de conta do próprio cliente. Transação e estorno: quem pagou ou quem
 * recebeu enxerga; só quem pagou pede estorno; aprovar/rejeitar é do ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/pix")
public class PaymentController {

    private final RegisterPixKey registerPixKey;
    private final DeletePixKey deletePixKey;
    private final GetPixKey getPixKey;
    private final SendPix sendPix;
    private final GetPixTransaction getPixTransaction;
    private final RequestPixRefund requestPixRefund;
    private final ApprovePixRefund approvePixRefund;
    private final RejectPixRefund rejectPixRefund;
    private final GetPixRefund getPixRefund;
    private final AccessGuard accessGuard;

    public PaymentController(RegisterPixKey registerPixKey, DeletePixKey deletePixKey, GetPixKey getPixKey,
                             SendPix sendPix, GetPixTransaction getPixTransaction, RequestPixRefund requestPixRefund,
                             ApprovePixRefund approvePixRefund, RejectPixRefund rejectPixRefund,
                             GetPixRefund getPixRefund, AccessGuard accessGuard) {
        this.registerPixKey = registerPixKey;
        this.deletePixKey = deletePixKey;
        this.getPixKey = getPixKey;
        this.sendPix = sendPix;
        this.getPixTransaction = getPixTransaction;
        this.requestPixRefund = requestPixRefund;
        this.approvePixRefund = approvePixRefund;
        this.rejectPixRefund = rejectPixRefund;
        this.getPixRefund = getPixRefund;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/keys")
    public ResponseEntity<RegisterPixKey.Output> registerKey(@RequestBody RegisterKeyRequest request) {
        accessGuard.checkAccount(request.accountId());
        RegisterPixKey.Output output = registerPixKey.execute(new RegisterPixKey.Input(request.accountId(), request.keyType(), request.keyValue()));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    @GetMapping("/keys/{pixKeyId}")
    public ResponseEntity<GetPixKey.Output> getKey(@PathVariable UUID pixKeyId) {
        accessGuard.checkPixKey(pixKeyId);
        return ResponseEntity.ok(getPixKey.execute(pixKeyId));
    }

    @DeleteMapping("/keys/{pixKeyId}")
    public ResponseEntity<Void> deleteKey(@PathVariable UUID pixKeyId) {
        accessGuard.checkPixKey(pixKeyId);
        deletePixKey.execute(pixKeyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/send")
    public ResponseEntity<SendPix.Output> sendPix(@RequestBody SendPixRequest request) {
        accessGuard.checkAccount(request.sourceAccountId()); // só sai dinheiro da própria conta
        SendPix.Output output = sendPix.execute(new SendPix.Input(request.sourceAccountId(), request.targetPixKey(), Money.of(request.amount()), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<GetPixTransaction.Output> getTransaction(@PathVariable UUID transactionId) {
        accessGuard.checkPixTransaction(transactionId);
        return ResponseEntity.ok(getPixTransaction.execute(transactionId));
    }

    @PostMapping("/refunds")
    public ResponseEntity<RequestPixRefund.Output> requestRefund(@RequestBody RequestRefundRequest request) {
        accessGuard.checkCanRequestRefund(request.originalTransactionId());
        RequestPixRefund.Output output = requestPixRefund.execute(new RequestPixRefund.Input(request.originalTransactionId(), request.reason(), request.refundAmount() != null ? Money.of(request.refundAmount()) : null));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<GetPixRefund.Output> getRefund(@PathVariable UUID refundId) {
        accessGuard.checkPixRefund(refundId);
        return ResponseEntity.ok(getPixRefund.execute(refundId));
    }

    /**
     * 202: a devolução do dinheiro é assíncrona; acompanhe pelo GET do estorno (COMPLETED ou FAILED).
     * Quem aprovou vem do token, não de parâmetro: trilha de auditoria não pode ser forjada pelo cliente.
     */
    @PostMapping("/refunds/{refundId}/approve")
    public ResponseEntity<Void> approveRefund(@PathVariable UUID refundId) {
        approvePixRefund.execute(refundId, accessGuard.currentUser().username());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/refunds/{refundId}/reject")
    public ResponseEntity<Void> rejectRefund(@PathVariable UUID refundId) {
        rejectPixRefund.execute(refundId, accessGuard.currentUser().username());
        return ResponseEntity.noContent().build();
    }

    public record RegisterKeyRequest(UUID accountId, PixKeyType keyType, String keyValue) {}
    public record SendPixRequest(UUID sourceAccountId, String targetPixKey,
                                 BigDecimal amount, String description) {}
    public record RequestRefundRequest(UUID originalTransactionId, String reason,
                                       BigDecimal refundAmount) {}
}
