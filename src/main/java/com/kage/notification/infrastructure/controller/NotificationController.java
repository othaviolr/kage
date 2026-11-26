package com.kage.notification.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.auth.infrastructure.security.AuthenticatedUser;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.MarkNotificationAsRead;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Caixa de entrada. O cliente vê sempre a própria (o customerId vem do token); o ADMIN informa de
 * qual cliente quer ver (?customerId=).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final ListNotifications listNotifications;
    private final MarkNotificationAsRead markNotificationAsRead;
    private final AccessGuard accessGuard;
    private final NotificationPreferences notificationPreferences;

    public NotificationController(ListNotifications listNotifications, MarkNotificationAsRead markNotificationAsRead,
                                  AccessGuard accessGuard, NotificationPreferences notificationPreferences) {
        this.notificationPreferences = notificationPreferences;
        this.listNotifications = listNotifications;
        this.markNotificationAsRead = markNotificationAsRead;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<ListNotifications.Output> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                         @RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(listNotifications.execute(new ListNotifications.Input(owner(customerId), unreadOnly)));
    }

    /** O que vai por push: categorias, quais estão ligadas e quais não dá para desligar. */
    @GetMapping("/preferences")
    public ResponseEntity<List<NotificationPreferences.Preference>> preferences(@RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(notificationPreferences.list(owner(customerId)));
    }

    /** Liga/desliga o push de uma categoria. Desligado, o aviso continua na caixa de entrada. */
    @PatchMapping("/preferences/{category}")
    public ResponseEntity<List<NotificationPreferences.Preference>> setPreference(@PathVariable String category,
                                                                                  @RequestBody PreferenceRequest request,
                                                                                  @RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(notificationPreferences.set(owner(customerId), category, request.enabled()));
    }

    public record PreferenceRequest(Boolean enabled) {
    }

    /** Horário silencioso: avisos não críticos nessa janela ficam só na caixa de entrada. */
    @GetMapping("/quiet-hours")
    public ResponseEntity<NotificationPreferences.QuietHoursOutput> quietHours(@RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(notificationPreferences.quietHours(owner(customerId)));
    }

    @PutMapping("/quiet-hours")
    public ResponseEntity<NotificationPreferences.QuietHoursOutput> setQuietHours(@RequestBody QuietHoursRequest request,
                                                                                  @RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(notificationPreferences.setQuietHours(owner(customerId), request.enabled(),
                request.start(), request.end()));
    }

    /** @param start/end "HH:mm"; a janela pode atravessar a meia-noite (22:00 a 07:00) */
    public record QuietHoursRequest(Boolean enabled, String start, String end) {
    }

    /** O cliente mexe sempre no próprio (do token); o ADMIN informa de qual cliente. */
    private UUID owner(UUID customerId) {
        AuthenticatedUser user = accessGuard.currentUser();
        if (user.isAdmin()) {
            if (customerId == null) throw new ValidationException("Informe o customerId do cliente");
            return customerId;
        }
        return user.customerId(); // ignora ?customerId= de cliente: não dá para mexer no de outro
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<MarkNotificationAsRead.Output> markAsRead(@PathVariable UUID id) {
        AuthenticatedUser user = accessGuard.currentUser();
        return ResponseEntity.ok(markNotificationAsRead.execute(
                new MarkNotificationAsRead.Input(id, user.isAdmin() ? null : user.customerId())));
    }
}
