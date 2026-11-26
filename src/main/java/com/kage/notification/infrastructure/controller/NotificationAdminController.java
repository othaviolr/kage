package com.kage.notification.infrastructure.controller;

import com.kage.notification.application.usecase.QuietHoursSummaries;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/** Backoffice das notificações (ADMIN pelo SecurityConfig). */
@RestController
@RequestMapping("/api/admin/notifications")
public class NotificationAdminController {

    private final QuietHoursSummaries quietHoursSummaries;
    private final Clock clock;

    public NotificationAdminController(QuietHoursSummaries quietHoursSummaries, Clock clock) {
        this.quietHoursSummaries = quietHoursSummaries;
        this.clock = clock;
    }

    /** Roda a rodada de resumos agora (ou num horário simulado, {@code at}), como o job faria. */
    @PostMapping("/quiet-hours-summaries/run")
    public ResponseEntity<QuietHoursSummaries.Output> runQuietHoursSummaries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        return ResponseEntity.ok(quietHoursSummaries.run(at != null ? at : LocalDateTime.now(clock)));
    }
}
