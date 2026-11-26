package com.kage.notification.infrastructure.config;

import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.MarkNotificationAsRead;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.notification.application.usecase.Notify;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.infrastructure.persistence.JdbcNotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import com.kage.notification.domain.service.RecipientDirectory;
import com.kage.notification.infrastructure.RecipientDirectoryImpl;
import com.kage.notification.infrastructure.channel.LoggingNotificationChannel;
import com.kage.notification.infrastructure.messaging.NotificationEventConsumer;
import com.kage.notification.infrastructure.persistence.NotificationRepositoryImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

@Configuration
public class NotificationConfig {

    @Bean
    public NotificationRepository notificationRepository(JdbcTemplate jdbcTemplate) {
        return new NotificationRepositoryImpl(jdbcTemplate);
    }

    /** Troque por um canal real (FCM, SES...) declarando outro bean NotificationChannel. */
    @Bean
    @ConditionalOnMissingBean(NotificationChannel.class)
    public NotificationChannel notificationChannel() {
        return new LoggingNotificationChannel();
    }

    @Bean
    public RecipientDirectory recipientDirectory(AccountRepository accountRepository, CardRepository cardRepository) {
        return new RecipientDirectoryImpl(accountRepository, cardRepository);
    }

    @Bean
    public NotificationPreferenceRepository notificationPreferenceRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcNotificationPreferenceRepository(jdbcTemplate);
    }

    @Bean
    public NotificationPreferences notificationPreferences(NotificationPreferenceRepository repository, Clock clock) {
        return new NotificationPreferences(repository, clock);
    }

    @Bean
    public Notify notify(NotificationRepository notificationRepository, NotificationChannel notificationChannel, Clock clock,
                         NotificationPreferenceRepository notificationPreferenceRepository) {
        return new Notify(notificationRepository, notificationChannel, clock, notificationPreferenceRepository);
    }

    @Bean
    public ListNotifications listNotifications(NotificationRepository notificationRepository) {
        return new ListNotifications(notificationRepository);
    }

    @Bean
    public MarkNotificationAsRead markNotificationAsRead(NotificationRepository notificationRepository, Clock clock) {
        return new MarkNotificationAsRead(notificationRepository, clock);
    }

    @Bean
    public NotificationEventConsumer notificationEventConsumer(Notify notify, RecipientDirectory recipientDirectory) {
        return new NotificationEventConsumer(notify, recipientDirectory);
    }
}
