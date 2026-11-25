package com.kage.boleto.infrastructure.controller;

import com.kage.boleto.application.usecase.IssueBoletos;
import com.kage.boleto.application.usecase.PayBoletos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Backoffice (ADMIN): simula a compensação — um boleto do Kage pago em outro banco. */
@RestController
@RequestMapping("/api/admin/boletos")
public class BoletoAdminController {

    private final PayBoletos payBoletos;

    public BoletoAdminController(PayBoletos payBoletos) {
        this.payBoletos = payBoletos;
    }

    @PostMapping("/{id}/settle")
    public ResponseEntity<IssueBoletos.BoletoView> settleFromClearing(@PathVariable UUID id) {
        return ResponseEntity.ok(payBoletos.settleFromClearing(id));
    }
}
