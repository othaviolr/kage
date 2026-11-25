package com.kage.savings.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.savings.application.usecase.SavingsBoxes;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Caixinhas do cliente. Criar e listar ficam sob a conta (a caixinha pertence a uma conta); o
 * resto, pelo id da caixinha.
 */
@RestController
public class SavingsBoxController {

    public record CreateRequest(String name, BigDecimal goalAmount) {}
    public record AmountRequest(BigDecimal amount) {}

    private final SavingsBoxes savingsBoxes;
    private final AccessGuard accessGuard;

    public SavingsBoxController(SavingsBoxes savingsBoxes, AccessGuard accessGuard) {
        this.savingsBoxes = savingsBoxes;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/api/accounts/{accountId}/savings-boxes")
    public ResponseEntity<SavingsBoxes.BoxView> create(@PathVariable UUID accountId, @RequestBody CreateRequest request) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savingsBoxes.create(accountId, request.name(), request.goalAmount()));
    }

    @GetMapping("/api/accounts/{accountId}/savings-boxes")
    public ResponseEntity<List<SavingsBoxes.BoxView>> list(@PathVariable UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(savingsBoxes.listActive(accountId));
    }

    @GetMapping("/api/savings-boxes/{id}")
    public ResponseEntity<SavingsBoxes.Detail> get(@PathVariable UUID id) {
        accessGuard.checkSavingsBox(id);
        return ResponseEntity.ok(savingsBoxes.get(id));
    }

    @PostMapping("/api/savings-boxes/{id}/deposit")
    public ResponseEntity<SavingsBoxes.BoxView> deposit(@PathVariable UUID id, @RequestBody AmountRequest request) {
        accessGuard.checkSavingsBox(id);
        return ResponseEntity.ok(savingsBoxes.deposit(id, amount(request)));
    }

    /** Valor BRUTO: sai da caixinha; a conta recebe o líquido de IOF e IR. */
    @PostMapping("/api/savings-boxes/{id}/withdraw")
    public ResponseEntity<SavingsBoxes.WithdrawalResult> withdraw(@PathVariable UUID id, @RequestBody AmountRequest request) {
        accessGuard.checkSavingsBox(id);
        return ResponseEntity.ok(savingsBoxes.withdraw(id, amount(request)));
    }

    /** Simula o resgate (sem amount: tudo) — quanto sairia de imposto e quanto cairia na conta. */
    @GetMapping("/api/savings-boxes/{id}/withdrawal-preview")
    public ResponseEntity<SavingsBoxes.WithdrawalView> previewWithdrawal(@PathVariable UUID id,
                                                                         @RequestParam(required = false) BigDecimal amount) {
        accessGuard.checkSavingsBox(id);
        return ResponseEntity.ok(savingsBoxes.previewWithdrawal(id, amount));
    }

    /** Resgata o saldo inteiro (líquido de impostos) para a conta e encerra. */
    @DeleteMapping("/api/savings-boxes/{id}")
    public ResponseEntity<SavingsBoxes.WithdrawalResult> close(@PathVariable UUID id) {
        accessGuard.checkSavingsBox(id);
        return ResponseEntity.ok(savingsBoxes.close(id));
    }

    private static BigDecimal amount(AmountRequest request) {
        if (request == null || request.amount() == null) throw new ValidationException("Valor é obrigatório");
        return request.amount();
    }
}
