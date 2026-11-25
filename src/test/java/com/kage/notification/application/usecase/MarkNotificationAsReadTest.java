package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarkNotificationAsReadTest {

    private static final Instant NOW = Instant.parse("2026-11-25T13:00:00Z");

    @Mock NotificationRepository repository;

    MarkNotificationAsRead markAsRead;
    UUID owner;
    Notification notification;

    @BeforeEach
    void setUp() {
        markAsRead = new MarkNotificationAsRead(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        owner = UUID.randomUUID();
        notification = Notification.create(owner, NotificationType.DEPOSIT_RECEIVED, "Depósito recebido", "x",
                null, "evt-1", LocalDateTime.of(2026, 11, 25, 9, 0));
    }

    @Test
    void execute_deveMarcarComoLida_quandoDono() {
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        var output = markAsRead.execute(new MarkNotificationAsRead.Input(notification.getId(), owner));

        assertThat(output.readAt()).isEqualTo(LocalDateTime.of(2026, 11, 25, 13, 0));
        verify(repository).markAsRead(notification);
    }

    @Test
    void execute_deveResponder404_quandoNotificacaoDeOutroCliente() {
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        assertThatThrownBy(() -> markAsRead.execute(new MarkNotificationAsRead.Input(notification.getId(), UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        verify(repository, never()).markAsRead(any());
    }

    @Test
    void marcarDuasVezes_deveManterAPrimeiraLeitura() {
        LocalDateTime first = LocalDateTime.of(2026, 11, 25, 10, 0);
        notification.markAsRead(first);
        notification.markAsRead(first.plusHours(2));

        assertThat(notification.getReadAt()).isEqualTo(first);
    }
}
