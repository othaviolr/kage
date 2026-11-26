package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Grava o aviso na caixa de entrada e tenta entregá-lo pelo canal externo.
 *
 * - Reentrega do mesmo evento não gera aviso repetido (nem push repetido): saveIfNew devolve false.
 * - Falha no canal não volta a mensagem para a fila: o aviso já está na caixa de entrada, que é a
 *   fonte da verdade; reprocessar só duplicaria o push para quem já recebeu.
 * - Categoria desligada pelo cliente: o aviso vai para a caixa de entrada sem push (a não ser que
 *   seja crítico ou de categoria obrigatória — ver NotificationType.shouldPush).
 */
public class Notify {

    private static final Logger logger = LoggerFactory.getLogger(Notify.class);

    public record Input(UUID customerId, NotificationType type, NotificationTemplates.Text text, UUID referenceId,
                        String sourceEventId) {}

    private final NotificationRepository notificationRepository;
    private final NotificationChannel channel;
    private final Clock clock;
    private final NotificationPreferenceRepository preferences;

    public Notify(NotificationRepository notificationRepository, NotificationChannel channel, Clock clock) {
        this(notificationRepository, channel, clock, null);
    }

    public Notify(NotificationRepository notificationRepository, NotificationChannel channel, Clock clock,
                  NotificationPreferenceRepository preferences) {
        this.notificationRepository = notificationRepository;
        this.channel = channel;
        this.clock = clock;
        this.preferences = preferences;
    }

    /** @return true se o aviso é novo; false se era reentrega */
    public boolean execute(Input input) {
        boolean push = preferences == null || input.type().shouldPush(preferences.disabledFor(input.customerId()));
        Notification notification = Notification.create(input.customerId(), input.type(), input.text().title(),
                input.text().message(), input.referenceId(), input.sourceEventId(), LocalDateTime.now(clock), push);

        if (!notificationRepository.saveIfNew(notification)) {
            logger.debug("Notificação do evento {} já existe, ignorando reentrega", input.sourceEventId());
            return false;
        }

        if (!push) {
            logger.debug("Notificação {} só na caixa de entrada: o cliente desligou {}", notification.getId(),
                    input.type().category());
            return true;
        }
        try {
            channel.deliver(notification);
        } catch (RuntimeException e) {
            logger.warn("Falha ao entregar notificação {} pelo canal externo; segue disponível no app", notification.getId(), e);
        }
        return true;
    }
}
