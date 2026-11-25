package com.kage.savings.infrastructure.controller;

import com.kage.savings.application.usecase.RunSavingsYield;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Backoffice (ADMIN): rodar o rendimento na hora — reprocessar um dia, ou simular uma data
 * (?date=2026-12-01 rende os dias úteis até 30/11). Rodar de novo para a mesma data não rende duas vezes.
 */
@RestController
@RequestMapping("/api/admin/savings")
public class SavingsAdminController {

    private final RunSavingsYield runSavingsYield;
    private final Clock clock;

    public SavingsAdminController(RunSavingsYield runSavingsYield, Clock clock) {
        this.runSavingsYield = runSavingsYield;
        this.clock = clock;
    }

    @PostMapping("/yield/run")
    public ResponseEntity<RunSavingsYield.Output> run(@RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(runSavingsYield.execute(date != null ? date : LocalDate.now(clock)));
    }
}
