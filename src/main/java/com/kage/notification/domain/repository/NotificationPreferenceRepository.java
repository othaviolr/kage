package com.kage.notification.domain.repository;

import com.kage.notification.domain.enums.NotificationCategory;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/** Preferências de push do cliente. Sem registro, a categoria está ligada. */
public interface NotificationPreferenceRepository {

    Set<NotificationCategory> disabledFor(UUID customerId);

    void set(UUID customerId, NotificationCategory category, boolean enabled, LocalDateTime now);
}
