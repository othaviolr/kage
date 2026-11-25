package com.kage.payment.infrastructure.controller;

import com.kage.payment.application.usecase.RunScheduledPix;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/** Backoffice (ADMIN, /api/admin/**): disparar os PIX agendados na hora — reprocessar ou simular uma data. */
@RestController
@RequestMapping("/api/admin/pix/scheduled")
public class ScheduledPixAdminController {

    private final RunScheduledPix runScheduledPix;
    private final Clock clock;

    public ScheduledPixAdminController(RunScheduledPix runScheduledPix, Clock clock) {
        this.runScheduledPix = runScheduledPix;
        this.clock = clock;
    }

    @PostMapping("/run")
    public ResponseEntity<RunScheduledPix.Output> run(@RequestParam(required = false) LocalDate date) {
        return ResponseEntity.ok(runScheduledPix.execute(date != null ? date : LocalDate.now(clock)));
    }
}
