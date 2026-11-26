package com.kage.loan.infrastructure.controller;

import com.kage.loan.application.usecase.RunLoanDebits;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/** Backoffice do empréstimo (ADMIN pelo SecurityConfig). */
@RestController
@RequestMapping("/api/admin/loans")
public class LoanAdminController {

    private final RunLoanDebits runLoanDebits;
    private final Clock clock;

    public LoanAdminController(RunLoanDebits runLoanDebits, Clock clock) {
        this.runLoanDebits = runLoanDebits;
        this.clock = clock;
    }

    /** Roda o débito automático das parcelas de um dia (hoje, ou simulado), como o job faria. */
    @PostMapping("/debits/run")
    public ResponseEntity<RunLoanDebits.Output> runDebits(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(runLoanDebits.execute(date != null ? date : LocalDate.now(clock)));
    }
}
