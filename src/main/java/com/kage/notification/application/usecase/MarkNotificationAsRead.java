package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

public class MarkNotificationAsRead {

    /** @param customerId dono esperado; nulo para o ADMIN (backoffice pode marcar qualquer uma) */
    public record Input(UUID notificationId, UUID customerId) {}
    public record Output(UUID id, LocalDateTime readAt) {}

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public MarkNotificationAsRead(NotificationRepository notificationRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    public Output execute(Input input) {
        Notification notification = notificationRepository.findById(input.notificationId())
                // aviso de outro cliente responde igual a inexistente (mesma política do AccessGuard)
                .filter(n -> input.customerId() == null || n.getCustomerId().equals(input.customerId()))
                .orElseThrow(() -> new NotFoundException("Notificação não encontrada"));

        notification.markAsRead(LocalDateTime.now(clock));
        notificationRepository.markAsRead(notification);
        return new Output(notification.getId(), notification.getReadAt());
    }
}
