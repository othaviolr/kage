package com.kage.payment.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.payment.application.usecase.PixAutomatic;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Frequency;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Party;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PIX Automático. Quem recebe: pedir autorização, mandar cobrança, cancelar. Quem paga: autorizar
 * (com teto opcional), recusar, cancelar uma cobrança até a véspera, cancelar a autorização.
 */
@RestController
@RequestMapping("/api/pix/automatic")
public class PixAutomaticController {

    private final PixAutomatic pixAutomatic;
    private final AccessGuard accessGuard;

    public PixAutomaticController(PixAutomatic pixAutomatic, AccessGuard accessGuard) {
        this.pixAutomatic = pixAutomatic;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/authorizations")
    public ResponseEntity<PixAutomatic.View> request(@RequestBody AuthorizationRequest request) {
        accessGuard.checkAccount(request.receiverAccountId()); // quem pede é o dono da conta recebedora
        PixAutomatic.View view = pixAutomatic.request(new PixAutomatic.RequestInput(request.receiverAccountId(),
                request.receiverPixKey(), request.payerPixKey(), request.description(), request.contractReference(),
                request.frequency(), request.fixedAmount() == null ? null : Money.of(request.fixedAmount()),
                request.startDate(), request.endDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** Autorizações em que a conta paga ou recebe. */
    @GetMapping("/authorizations")
    public ResponseEntity<List<PixAutomatic.View>> list(@RequestParam UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(pixAutomatic.listByAccount(accountId));
    }

    @GetMapping("/authorizations/{id}")
    public ResponseEntity<PixAutomatic.View> get(@PathVariable UUID id) {
        accessGuard.pixAutomaticRole(id);
        return ResponseEntity.ok(pixAutomatic.get(id));
    }

    @PostMapping("/authorizations/{id}/approve")
    public ResponseEntity<PixAutomatic.View> approve(@PathVariable UUID id,
                                                     @RequestBody(required = false) ApproveRequest request) {
        accessGuard.checkPixAutomaticRole(id, Party.PAYER);
        BigDecimal max = request == null ? null : request.maxAmount();
        return ResponseEntity.ok(pixAutomatic.approve(id, max == null ? null : Money.of(max)));
    }

    @PostMapping("/authorizations/{id}/reject")
    public ResponseEntity<PixAutomatic.View> reject(@PathVariable UUID id) {
        accessGuard.checkPixAutomaticRole(id, Party.PAYER);
        return ResponseEntity.ok(pixAutomatic.reject(id));
    }

    /** Qualquer das partes encerra; fica registrado quem foi. */
    @DeleteMapping("/authorizations/{id}")
    public ResponseEntity<PixAutomatic.View> cancel(@PathVariable UUID id) {
        Party role = accessGuard.pixAutomaticRole(id);
        return ResponseEntity.ok(pixAutomatic.cancel(id, role));
    }

    @PostMapping("/authorizations/{id}/charges")
    public ResponseEntity<PixAutomatic.ChargeView> submitCharge(@PathVariable UUID id, @RequestBody ChargeRequest request) {
        accessGuard.checkPixAutomaticRole(id, Party.RECEIVER);
        return ResponseEntity.status(HttpStatus.CREATED).body(pixAutomatic.submitCharge(id,
                request.amount() == null ? null : Money.of(request.amount()), request.dueDate()));
    }

    @DeleteMapping("/charges/{chargeId}")
    public ResponseEntity<PixAutomatic.ChargeView> cancelCharge(@PathVariable UUID chargeId) {
        accessGuard.checkPixAutomaticChargePayer(chargeId);
        return ResponseEntity.ok(pixAutomatic.cancelCharge(chargeId));
    }

    public record AuthorizationRequest(UUID receiverAccountId, String receiverPixKey, String payerPixKey,
                                       String description, String contractReference, Frequency frequency,
                                       BigDecimal fixedAmount, LocalDate startDate, LocalDate endDate) {}

    public record ApproveRequest(BigDecimal maxAmount) {}

    public record ChargeRequest(BigDecimal amount, LocalDate dueDate) {}
}
