package com.kage.payment.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.payment.application.usecase.PixCharges;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Cobrar com PIX copia e cola: só o dono da conta que recebe gera, vê e cancela. */
@RestController
@RequestMapping("/api/pix/charges")
public class PixChargeController {

    private final PixCharges pixCharges;
    private final AccessGuard accessGuard;

    public PixChargeController(PixCharges pixCharges, AccessGuard accessGuard) {
        this.pixCharges = pixCharges;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<PixCharges.ChargeView> create(@RequestBody CreateRequest request) {
        accessGuard.checkAccount(request.accountId());
        return ResponseEntity.status(HttpStatus.CREATED).body(pixCharges.create(request.accountId(), request.pixKey(),
                request.amount(), request.message(), request.singleUse(), request.validForDays()));
    }

    @GetMapping
    public ResponseEntity<List<PixCharges.ChargeView>> list(@RequestParam UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(pixCharges.list(accountId));
    }

    @GetMapping("/{chargeId}")
    public ResponseEntity<PixCharges.ChargeView> get(@PathVariable UUID chargeId) {
        accessGuard.checkAccount(pixCharges.accountOf(chargeId));
        return ResponseEntity.ok(pixCharges.get(chargeId));
    }

    @DeleteMapping("/{chargeId}")
    public ResponseEntity<PixCharges.ChargeView> cancel(@PathVariable UUID chargeId) {
        accessGuard.checkAccount(pixCharges.accountOf(chargeId));
        return ResponseEntity.ok(pixCharges.cancel(chargeId));
    }

    /**
     * @param amount    nulo = quem paga escolhe o valor
     * @param singleUse    nulo = uso único quando tem valor
     * @param validForDays nulo = sem validade; de 1 a 365 (vale até o fim do último dia)
     */
    public record CreateRequest(UUID accountId, String pixKey, BigDecimal amount, String message, Boolean singleUse,
                                Integer validForDays) {}
}
