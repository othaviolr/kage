package com.kage.notification.domain.service;

import com.kage.notification.domain.entity.Notification;

/**
 * Canal de entrega para fora do app (push, e-mail, SMS). Best-effort: a notificação já está salva
 * na caixa de entrada antes; uma falha de entrega não a desfaz.
 */
public interface NotificationChannel {

    void deliver(Notification notification);
}
