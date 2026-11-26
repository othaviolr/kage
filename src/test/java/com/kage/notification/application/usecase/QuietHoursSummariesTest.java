package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.enums.PushSilencedReason;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import com.kage.notification.domain.valueobject.QuietHours;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuietHoursSummariesTest {

    private static final QuietHours NIGHT = new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0));

    @Mock NotificationPreferenceRepository preferences;
    @Mock NotificationRepository repository;
    @Mock NotificationChannel channel;

    private final UUID customerId = UUID.randomUUID();
    private QuietHoursSummaries summaries;

    @BeforeEach
    void setUp() {
        summaries = new QuietHoursSummaries(preferences, repository, channel, Clock.systemDefaultZone());
    }

    @Test
    void janela_queAtravessaAMeiaNoite() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 27, 7, 5);
        LocalDateTime end = QuietHoursSummaries.lastEnd(NIGHT, at);

        assertThat(end).isEqualTo(LocalDateTime.of(2026, 11, 27, 7, 0));
        assertThat(QuietHoursSummaries.startOf(NIGHT, end)).isEqualTo(LocalDateTime.of(2026, 11, 26, 22, 0));
        // antes das 7h, o último fim foi o de ontem
        assertThat(QuietHoursSummaries.lastEnd(NIGHT, LocalDateTime.of(2026, 11, 27, 6, 0)))
                .isEqualTo(LocalDateTime.of(2026, 11, 26, 7, 0));
    }

    @Test
    void fimDaJanela_mandaUmResumoComAsContasPorCategoria() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 27, 7, 5);
        when(preferences.enabledQuietHours()).thenReturn(Map.of(customerId, NIGHT));
        when(repository.countSilenced(customerId, PushSilencedReason.QUIET_HOURS, LocalDateTime.of(2026, 11, 26, 22, 0),
                LocalDateTime.of(2026, 11, 27, 7, 0)))
                .thenReturn(Map.of(NotificationType.PIX_RECEIVED, 2, NotificationType.PIX_SENT, 1,
                        NotificationType.CARD_PURCHASE_APPROVED, 2));
        when(repository.saveIfNew(any())).thenReturn(true);

        QuietHoursSummaries.Output output = summaries.run(at);

        assertThat(output.summariesSent()).isEqualTo(1);
        ArgumentCaptor<Notification> summary = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveIfNew(summary.capture());
        assertThat(summary.getValue().getType()).isEqualTo(NotificationType.QUIET_HOURS_SUMMARY);
        assertThat(summary.getValue().getTitle()).isEqualTo("Resumo do horário silencioso");
        assertThat(summary.getValue().getMessage()).isEqualTo("Enquanto o horário silencioso estava ligado (22:00 às 07:00), "
                + "chegaram 5 avisos: 3 de PIX, 2 de compras no cartão. Eles estão na sua caixa de entrada.");
        assertThat(summary.getValue().getSourceEventId())
                .isEqualTo(QuietHoursSummaries.sourceEventId(customerId, LocalDateTime.of(2026, 11, 27, 7, 0)))
                .hasSizeLessThanOrEqualTo(64);
        assertThat(summary.getValue().isPushed()).isTrue();
        verify(channel).deliver(summary.getValue());
    }

    @Test
    void resumoJaMandado_naoMandaDeNovo() {
        when(preferences.enabledQuietHours()).thenReturn(Map.of(customerId, NIGHT));
        when(repository.countSilenced(eq(customerId), any(), any(), any())).thenReturn(Map.of(NotificationType.DEPOSIT_RECEIVED, 1));
        when(repository.saveIfNew(any())).thenReturn(false);

        assertThat(summaries.run(LocalDateTime.of(2026, 11, 27, 7, 10)).summariesSent()).isZero();
        verify(channel, never()).deliver(any());
    }

    @Test
    void nadaSilenciado_ouJanelaAntigaDemais_naoMandaNada() {
        when(preferences.enabledQuietHours()).thenReturn(Map.of(customerId, NIGHT));
        when(repository.countSilenced(eq(customerId), any(), any(), any())).thenReturn(Map.of());

        assertThat(summaries.run(LocalDateTime.of(2026, 11, 27, 7, 10)).summariesSent()).isZero();

        // 13h: a janela acabou há 6h (a aplicação ficou fora?) — tarde demais para um resumo
        assertThat(summaries.run(LocalDateTime.of(2026, 11, 27, 13, 0)).summariesSent()).isZero();
        verify(repository, never()).saveIfNew(any());
    }

    @Test
    void umAvisoSo_noSingular() {
        when(preferences.enabledQuietHours()).thenReturn(Map.of(customerId, NIGHT));
        when(repository.countSilenced(eq(customerId), any(), any(), any())).thenReturn(Map.of(NotificationType.DEPOSIT_RECEIVED, 1));
        when(repository.saveIfNew(any())).thenReturn(true);

        summaries.run(LocalDateTime.of(2026, 11, 27, 7, 5));

        ArgumentCaptor<Notification> summary = ArgumentCaptor.forClass(Notification.class);
        verify(repository).saveIfNew(summary.capture());
        assertThat(summary.getValue().getMessage()).contains("chegou 1 aviso: 1 de depósitos e saques. Ele está");
    }
}
