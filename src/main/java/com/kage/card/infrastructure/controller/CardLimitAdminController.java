package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.CreditLimits;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/** Backoffice: roda a análise de limite de todos os cartões ativos, como o job mensal (ADMIN pelo SecurityConfig). */
@RestController
@RequestMapping("/api/admin/cards/limit-reviews")
public class CardLimitAdminController {

    private final CreditLimits creditLimits;
    private final Clock clock;

    public CardLimitAdminController(CreditLimits creditLimits, Clock clock) {
        this.creditLimits = creditLimits;
        this.clock = clock;
    }

    @PostMapping("/run")
    public ResponseEntity<CreditLimits.RunOutput> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(creditLimits.runReviews(date != null ? date : LocalDate.now(clock)));
    }
}
