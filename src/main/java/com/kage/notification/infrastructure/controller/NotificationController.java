package com.kage.notification.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.auth.infrastructure.security.AuthenticatedUser;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.MarkNotificationAsRead;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public NotificationController(ListNotifications listNotifications, MarkNotificationAsRead markNotificationAsRead,
                                  AccessGuard accessGuard) {
        this.listNotifications = listNotifications;
        this.markNotificationAsRead = markNotificationAsRead;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public ResponseEntity<ListNotifications.Output> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                         @RequestParam(required = false) UUID customerId) {
        AuthenticatedUser user = accessGuard.currentUser();
        UUID owner;
        if (user.isAdmin()) {
            if (customerId == null) throw new ValidationException("Informe o customerId do cliente");
            owner = customerId;
        } else {
            owner = user.customerId(); // ignora ?customerId= de cliente: não dá para ler a caixa de outro
        }
        return ResponseEntity.ok(listNotifications.execute(new ListNotifications.Input(owner, unreadOnly)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<MarkNotificationAsRead.Output> markAsRead(@PathVariable UUID id) {
        AuthenticatedUser user = accessGuard.currentUser();
        return ResponseEntity.ok(markNotificationAsRead.execute(
                new MarkNotificationAsRead.Input(id, user.isAdmin() ? null : user.customerId())));
    }
}
