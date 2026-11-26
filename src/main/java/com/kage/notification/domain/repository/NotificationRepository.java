package com.kage.notification.domain.repository;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.enums.PushSilencedReason;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    /** Avisos do cliente que ficaram sem push pelo {@code reason}, criados em [from, to), por tipo. */
    Map<NotificationType, Integer> countSilenced(UUID customerId, PushSilencedReason reason, LocalDateTime from,
                                                 LocalDateTime to);
}
