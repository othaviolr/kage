package com.kage.notification.application.usecase;

import com.kage.notification.domain.repository.NotificationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Caixa de entrada do cliente: os 50 avisos mais recentes e quantos ainda não foram lidos. */
public class ListNotifications {

    static final int LIMIT = 50;

    public record Input(UUID customerId, boolean unreadOnly) {}
    public record Item(UUID id, String type, String title, String message, UUID referenceId,
                       LocalDateTime createdAt, boolean read, boolean pushed) {}
    public record Output(int unreadCount, List<Item> notifications) {}

    private final NotificationRepository notificationRepository;

    public ListNotifications(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Output execute(Input input) {
        List<Item> items = notificationRepository.findByCustomer(input.customerId(), input.unreadOnly(), LIMIT).stream()
                .map(n -> new Item(n.getId(), n.getType().name(), n.getTitle(), n.getMessage(), n.getReferenceId(),
                        n.getCreatedAt(), n.isRead(), n.isPushed()))
                .toList();
        return new Output(notificationRepository.countUnread(input.customerId()), items);
    }
}
