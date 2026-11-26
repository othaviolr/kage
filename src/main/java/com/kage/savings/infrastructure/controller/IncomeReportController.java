package com.kage.savings.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.savings.application.usecase.GetIncomeReport;
import com.kage.savings.application.usecase.IncomeReportDocuments;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Informe de rendimentos do cliente (por CPF, juntando contas, caixinhas e empréstimos). Sem ano: o ano passado. */
@RestController
public class IncomeReportController {

    private final GetIncomeReport getIncomeReport;
    private final IncomeReportDocuments incomeReportDocuments;
    private final AccessGuard accessGuard;
    private final Clock clock;

    public IncomeReportController(GetIncomeReport getIncomeReport, IncomeReportDocuments incomeReportDocuments,
                                  AccessGuard accessGuard, Clock clock) {
        this.getIncomeReport = getIncomeReport;
        this.incomeReportDocuments = incomeReportDocuments;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @GetMapping("/api/customers/{customerId}/income-report")
    public ResponseEntity<GetIncomeReport.Output> get(@PathVariable UUID customerId,
                                                      @RequestParam(required = false) Integer year) {
        accessGuard.checkCustomer(customerId);
        return ResponseEntity.ok(getIncomeReport.execute(new GetIncomeReport.Input(customerId, yearOrLast(year))));
    }

    /** O mesmo informe em PDF, para guardar ou mandar ao contador. */
    @GetMapping("/api/customers/{customerId}/income-report/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID customerId, @RequestParam(required = false) Integer year) {
        accessGuard.checkCustomer(customerId);
        IncomeReportDocuments.Document document = incomeReportDocuments.pdf(customerId, yearOrLast(year));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileName()).build().toString())
                .body(document.content());
    }

    private int yearOrLast(Integer year) {
        return year != null ? year : LocalDate.now(clock).getYear() - 1;
    }
}
