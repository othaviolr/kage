package com.kage.notification.domain.repository;

import com.kage.notification.domain.entity.Notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    /** @return false se já existe notificação para o mesmo evento de origem (reentrega) */
    boolean saveIfNew(Notification notification);

    void markAsRead(Notification notification);

    Optional<Notification> findById(UUID id);

    /** Mais recentes primeiro. */
    List<Notification> findByCustomer(UUID customerId, boolean unreadOnly, int limit);

    int countUnread(UUID customerId);
}
