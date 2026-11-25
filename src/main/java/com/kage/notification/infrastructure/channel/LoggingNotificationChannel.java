package com.kage.notification.infrastructure.channel;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.service.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canal simulado: registra no log o que seria enviado por push/e-mail. Para enviar de verdade, basta
 * outra implementação de NotificationChannel (FCM, SES, Twilio...) — o resto do fluxo não muda.
 */
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger logger = LoggerFactory.getLogger(LoggingNotificationChannel.class);

    @Override
    public void deliver(Notification notification) {
        logger.info("[push] cliente={} tipo={} \"{}\" - {}", notification.getCustomerId(), notification.getType(),
                notification.getTitle(), notification.getMessage());
    }
}
