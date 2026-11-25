package com.kage.notification.domain.entity;

import com.kage.notification.domain.enums.NotificationType;
import com.kage.shared.domain.exception.ValidationException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Aviso na caixa de entrada do cliente. É a fonte da verdade do que foi avisado: o envio por push /
 * e-mail é uma tentativa em cima dela.
 *
 * @param sourceEventId id da mensagem que gerou o aviso: a mesma mensagem reentregue não gera outro
 */
public class Notification {

    private final UUID id;
    private final UUID customerId;
    private final NotificationType type;
    private final String title;
    private final String message;
    private final UUID referenceId;
    private final String sourceEventId;
    private final LocalDateTime createdAt;
    private LocalDateTime readAt;

    private Notification(UUID id, UUID customerId, NotificationType type, String title, String message,
                         UUID referenceId, String sourceEventId, LocalDateTime createdAt, LocalDateTime readAt) {
        this.id = id;
        this.customerId = customerId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.referenceId = referenceId;
        this.sourceEventId = sourceEventId;
        this.createdAt = createdAt;
        this.readAt = readAt;
    }

    public static Notification create(UUID customerId, NotificationType type, String title, String message,
                                      UUID referenceId, String sourceEventId, LocalDateTime now) {
        if (customerId == null) throw new ValidationException("Destinatário é obrigatório");
        if (type == null) throw new ValidationException("Tipo da notificação é obrigatório");
        if (title == null || title.isBlank()) throw new ValidationException("Título é obrigatório");
        if (sourceEventId == null || sourceEventId.isBlank()) throw new ValidationException("Evento de origem é obrigatório");
        return new Notification(UUID.randomUUID(), customerId, type, title, message, referenceId, sourceEventId, now, null);
    }

    public static Notification reconstitute(UUID id, UUID customerId, NotificationType type, String title, String message,
                                            UUID referenceId, String sourceEventId, LocalDateTime createdAt,
                                            LocalDateTime readAt) {
        return new Notification(id, customerId, type, title, message, referenceId, sourceEventId, createdAt, readAt);
    }

    /** Idempotente: marcar como lida de novo mantém a data da primeira leitura. */
    public void markAsRead(LocalDateTime now) {
        if (this.readAt == null) this.readAt = now;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public UUID getReferenceId() { return referenceId; }
    public String getSourceEventId() { return sourceEventId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getReadAt() { return readAt; }
}
