package com.kage.notification.domain.repository;

import com.kage.notification.domain.enums.NotificationCategory;

import com.kage.notification.domain.valueobject.QuietHours;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Preferências de push do cliente. Sem registro, a categoria está ligada. */
public interface NotificationPreferenceRepository {

    Set<NotificationCategory> disabledFor(UUID customerId);

    void set(UUID customerId, NotificationCategory category, boolean enabled, LocalDateTime now);

    /** Vazio = nunca configurou (vale o padrão, desligado). */
    Optional<QuietHours> quietHours(UUID customerId);

    void saveQuietHours(UUID customerId, QuietHours quietHours, LocalDateTime now);

    /** Fatura por e-mail no fechamento. Sem registro = ligada. */
    boolean invoiceEmailEnabled(UUID customerId);

    void setInvoiceEmail(UUID customerId, boolean enabled, LocalDateTime now);
}
