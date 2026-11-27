package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.DueDays;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dia de vencimento da fatura: os dias que dá para escolher e a troca (o fechamento acompanha). */
@RestController
@RequestMapping("/api/cards/{id}/due-day")
public class DueDayController {

    private final DueDays dueDays;
    private final AccessGuard accessGuard;

    public DueDayController(DueDays dueDays, AccessGuard accessGuard) {
        this.dueDays = dueDays;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<DueDays.Status> status(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(dueDays.status(id));
    }

    @PatchMapping
    public ResponseEntity<DueDays.Change> change(@PathVariable UUID id, @RequestBody(required = false) ChangeRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(dueDays.change(id, request == null ? null : request.dueDay()));
    }

    public record ChangeRequest(Integer dueDay) {
    }
}
