package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.RunBillingCycle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Operação do backoffice (ADMIN): rodar o ciclo de cobrança na hora, sem esperar o agendamento —
 * reprocessar um dia que falhou, ou simular uma data (?date=2026-12-21) para ver fechamento e
 * débito automático acontecendo.
 */
@RestController
@RequestMapping("/api/admin/billing")
public class BillingAdminController {

    private final RunBillingCycle runBillingCycle;
    private final Clock clock;

    public BillingAdminController(RunBillingCycle runBillingCycle, Clock clock) {
        this.runBillingCycle = runBillingCycle;
        this.clock = clock;
    }

    @PostMapping("/run")
    public ResponseEntity<RunBillingCycle.Output> run(@RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(runBillingCycle.execute(date != null ? date : LocalDate.now(clock)));
    }
}
