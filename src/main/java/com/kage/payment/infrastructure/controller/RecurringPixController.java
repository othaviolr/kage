package com.kage.payment.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.payment.application.usecase.RecurringPix;
import com.kage.payment.domain.entity.PixRecurrence;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PIX recorrente (semanal ou mensal). Cada ocorrência aparece também em /api/pix/scheduled; cancelar
 * uma ocorrência por lá pula só aquela vez. Para encerrar tudo, DELETE aqui.
 */
@RestController
@RequestMapping("/api/pix/recurring")
public class RecurringPixController {

    private final RecurringPix recurringPix;
    private final AccessGuard accessGuard;

    public RecurringPixController(RecurringPix recurringPix, AccessGuard accessGuard) {
        this.recurringPix = recurringPix;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<RecurringPix.View> create(@RequestBody CreateRequest request) {
        accessGuard.checkAccount(request.sourceAccountId());
        RecurringPix.View view = recurringPix.create(new RecurringPix.Input(request.sourceAccountId(),
                request.targetPixKey(), Money.of(request.amount()), request.description(), request.frequency(),
                request.startDate(), request.occurrences() == null ? 0 : request.occurrences()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public ResponseEntity<List<RecurringPix.View>> listActive(@RequestParam UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(recurringPix.listActive(accountId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecurringPix.View> get(@PathVariable UUID id) {
        accessGuard.checkPixRecurrence(id);
        return ResponseEntity.ok(recurringPix.get(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<RecurringPix.View> cancel(@PathVariable UUID id) {
        accessGuard.checkPixRecurrence(id);
        return ResponseEntity.ok(recurringPix.cancel(id));
    }

    public record CreateRequest(UUID sourceAccountId, String targetPixKey, BigDecimal amount, String description,
                                PixRecurrence.Frequency frequency, LocalDate startDate, Integer occurrences) {}
}
