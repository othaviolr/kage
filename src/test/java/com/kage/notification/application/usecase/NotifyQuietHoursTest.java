package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.enums.PushSilencedReason;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import com.kage.notification.domain.valueobject.QuietHours;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyQuietHoursTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final QuietHours NIGHT = new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0));

    @Mock NotificationRepository repository;
    @Mock NotificationChannel channel;
    @Mock NotificationPreferenceRepository preferences;

    private final UUID customerId = UUID.randomUUID();

    private Notify notifyAt(int hour, int minute) {
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 26, hour, minute).atZone(ZONE).toInstant(), ZONE);
        return new Notify(repository, channel, clock, preferences);
    }

    private Notify.Input input(NotificationType type) {
        return new Notify.Input(customerId, type, NotificationTemplates.depositReceived(new BigDecimal("10.00")),
                null, UUID.randomUUID().toString());
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveIfNew(captor.capture());
        return captor.getValue();
    }

    @Test
    void dentroDoHorario_avisoComumFicaSoNoApp() {
        when(preferences.quietHours(customerId)).thenReturn(Optional.of(NIGHT));
        when(repository.saveIfNew(any())).thenReturn(true);

        notifyAt(23, 30).execute(input(NotificationType.PIX_RECEIVED));

        assertThat(saved().isPushed()).isFalse();
        assertThat(saved().getSilencedReason()).isEqualTo(PushSilencedReason.QUIET_HOURS);
        verify(channel, never()).deliver(any());
    }

    @Test
    void dentroDoHorario_obrigatoriaNaoCriticaTambemEspera() {
        when(preferences.quietHours(customerId)).thenReturn(Optional.of(NIGHT));
        when(repository.saveIfNew(any())).thenReturn(true);

        notifyAt(2, 0).execute(input(NotificationType.INVOICE_CLOSED)); // fatura fechou de madrugada

        assertThat(saved().getSilencedReason()).isEqualTo(PushSilencedReason.QUIET_HOURS);
    }

    @Test
    void dentroDoHorario_criticoVaiPorPush() {
        when(repository.saveIfNew(any())).thenReturn(true);

        notifyAt(3, 0).execute(input(NotificationType.NEW_DEVICE_LOGIN));

        assertThat(saved().isPushed()).isTrue();
        verify(channel).deliver(any());
        verify(preferences, never()).quietHours(any()); // crítico nem consulta
    }

    @Test
    void foraDoHorario_valeACategoria() {
        when(preferences.quietHours(customerId)).thenReturn(Optional.of(NIGHT));
        when(preferences.disabledFor(customerId)).thenReturn(EnumSet.of(NotificationCategory.PIX));
        when(repository.saveIfNew(any())).thenReturn(true);

        notifyAt(12, 0).execute(input(NotificationType.PIX_RECEIVED));

        assertThat(saved().getSilencedReason()).isEqualTo(PushSilencedReason.CATEGORY_DISABLED);
    }

    @Test
    void semConfiguracao_padraoDesligado_vaiPorPush() {
        when(preferences.quietHours(customerId)).thenReturn(Optional.empty());
        when(preferences.disabledFor(customerId)).thenReturn(EnumSet.noneOf(NotificationCategory.class));
        when(repository.saveIfNew(any())).thenReturn(true);

        notifyAt(23, 30).execute(input(NotificationType.PIX_RECEIVED));

        assertThat(saved().isPushed()).isTrue();
        assertThat(saved().getSilencedReason()).isNull();
    }

    @Test
    void configurar_mantemOHorarioQuandoSoLiga_eValida() {
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 11, 26, 23, 0).atZone(ZONE).toInstant(), ZONE);
        NotificationPreferences useCase = new NotificationPreferences(preferences, clock);
        lenient().when(preferences.quietHours(customerId)).thenReturn(Optional.empty());

        NotificationPreferences.QuietHoursOutput on = useCase.setQuietHours(customerId, true, null, null);

        assertThat(on.start()).isEqualTo("22:00");
        assertThat(on.end()).isEqualTo("07:00");
        assertThat(on.activeNow()).isTrue();
        verify(preferences).saveQuietHours(eq(customerId), eq(NIGHT), any());

        assertThatThrownBy(() -> useCase.setQuietHours(customerId, true, "25:00", "07:00")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> useCase.setQuietHours(customerId, true, "22:00", "22:00")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> useCase.setQuietHours(customerId, null, null, null)).isInstanceOf(ValidationException.class);
    }
}
