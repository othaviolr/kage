package com.kage.boleto.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.boleto.application.usecase.BoletoReceipts;
import com.kage.boleto.application.usecase.IssueBoletos;
import com.kage.boleto.application.usecase.PayBoletos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Boletos. Emitir (para receber) e pagar ficam sob a conta; consultar um código antes de pagar é
 * aberto a qualquer cliente autenticado — como no app do banco, quem tem o código vê quem recebe.
 */
@RestController
public class BoletoController {

    public record PayRequest(String code, BigDecimal amount) {}

    private final IssueBoletos issueBoletos;
    private final PayBoletos payBoletos;
    private final AccessGuard accessGuard;
    private final BoletoReceipts boletoReceipts;

    public BoletoController(IssueBoletos issueBoletos, PayBoletos payBoletos, AccessGuard accessGuard,
                            BoletoReceipts boletoReceipts) {
        this.boletoReceipts = boletoReceipts;
        this.issueBoletos = issueBoletos;
        this.payBoletos = payBoletos;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/api/accounts/{accountId}/boletos")
    public ResponseEntity<IssueBoletos.BoletoView> issue(@PathVariable UUID accountId, @RequestBody IssueBoletos.Input input) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(issueBoletos.issue(accountId, input));
    }

    @GetMapping("/api/accounts/{accountId}/boletos")
    public ResponseEntity<List<IssueBoletos.BoletoView>> listIssued(@PathVariable UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(issueBoletos.listByAccount(accountId));
    }

    @GetMapping("/api/boletos/{id}")
    public ResponseEntity<IssueBoletos.BoletoView> get(@PathVariable UUID id) {
        accessGuard.checkBoleto(id);
        return ResponseEntity.ok(issueBoletos.get(id));
    }

    @DeleteMapping("/api/boletos/{id}")
    public ResponseEntity<IssueBoletos.BoletoView> cancel(@PathVariable UUID id) {
        accessGuard.checkBoleto(id);
        return ResponseEntity.ok(issueBoletos.cancel(id));
    }

    /** Linha digitável (47) ou código de barras (44): quem recebe, valor, vencimento e encargos de hoje. */
    @GetMapping("/api/boletos/lookup")
    public ResponseEntity<PayBoletos.Lookup> lookup(@RequestParam String code) {
        return ResponseEntity.ok(payBoletos.lookup(code));
    }

    @PostMapping("/api/accounts/{accountId}/boleto-payments")
    public ResponseEntity<PayBoletos.PaymentView> pay(@PathVariable UUID accountId, @RequestBody PayRequest request) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(payBoletos.pay(accountId, request.code(), request.amount()));
    }

    @GetMapping("/api/accounts/{accountId}/boleto-payments")
    public ResponseEntity<List<PayBoletos.PaymentView>> listPayments(@PathVariable UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(payBoletos.listPayments(accountId));
    }

    /** Comprovante do pagamento: só quem pagou. */
    @GetMapping("/api/boleto-payments/{paymentId}/receipt")
    public ResponseEntity<BoletoReceipts.Receipt> receipt(@PathVariable UUID paymentId) {
        accessGuard.checkBoletoPayment(paymentId);
        return ResponseEntity.ok(boletoReceipts.of(paymentId));
    }
}
