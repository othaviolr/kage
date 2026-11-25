package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyTest {

    @Mock NotificationRepository repository;
    @Mock NotificationChannel channel;

    Notify notify;

    @BeforeEach
    void setUp() {
        notify = new Notify(repository, channel, Clock.systemUTC());
    }

    private Notify.Input input(String eventId) {
        return new Notify.Input(UUID.randomUUID(), NotificationType.PIX_RECEIVED,
                NotificationTemplates.pixReceived(new BigDecimal("1234.56")), UUID.randomUUID(), eventId);
    }

    @Test
    void execute_deveSalvarEEntregar_quandoEventoNovo() {
        when(repository.saveIfNew(any())).thenReturn(true);

        assertThat(notify.execute(input("evt-1"))).isTrue();
        verify(channel).deliver(any(Notification.class));
    }

    @Test
    void execute_naoDeveEntregarDeNovo_quandoReentrega() {
        when(repository.saveIfNew(any())).thenReturn(false);

        assertThat(notify.execute(input("evt-1"))).isFalse();
        verify(channel, never()).deliver(any());
    }

    @Test
    void execute_naoDeveFalhar_quandoCanalExternoCai() {
        // o aviso já está na caixa de entrada; propagar faria o Rabbit reentregar e duplicar o push
        when(repository.saveIfNew(any())).thenReturn(true);
        doThrow(new IllegalStateException("FCM fora do ar")).when(channel).deliver(any());

        assertThat(notify.execute(input("evt-1"))).isTrue();
    }

    @Test
    void templates_deveFormatarDinheiroEDataNoPadraoBrasileiro() {
        assertThat(NotificationTemplates.pixReceived(new BigDecimal("1234.56")).message()).isEqualTo("Caíram R$ 1.234,56 na sua conta.");
        assertThat(NotificationTemplates.invoiceClosed("2026-11", new BigDecimal("1500.00"), LocalDate.of(2026, 12, 20)).message())
                .isEqualTo("A fatura de 11/2026 fechou em R$ 1.500,00 e vence em 20/12.");
    }
}
