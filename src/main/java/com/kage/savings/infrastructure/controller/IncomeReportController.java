package com.kage.savings.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.savings.application.usecase.GetIncomeReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Informe de rendimentos do cliente (por CPF, juntando contas e caixinhas). Sem ano: o ano passado. */
@RestController
public class IncomeReportController {

    private final GetIncomeReport getIncomeReport;
    private final AccessGuard accessGuard;
    private final Clock clock;

    public IncomeReportController(GetIncomeReport getIncomeReport, AccessGuard accessGuard, Clock clock) {
        this.getIncomeReport = getIncomeReport;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @GetMapping("/api/customers/{customerId}/income-report")
    public ResponseEntity<GetIncomeReport.Output> get(@PathVariable UUID customerId,
                                                      @RequestParam(required = false) Integer year) {
        accessGuard.checkCustomer(customerId);
        int reportYear = year != null ? year : LocalDate.now(clock).getYear() - 1;
        return ResponseEntity.ok(getIncomeReport.execute(new GetIncomeReport.Input(customerId, reportYear)));
    }
}
