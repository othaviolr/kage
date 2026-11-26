package com.kage.notification.application.usecase;

import com.kage.notification.domain.entity.Notification;
import com.kage.notification.domain.enums.NotificationCategory;
import com.kage.notification.domain.enums.NotificationType;
import com.kage.notification.domain.repository.NotificationPreferenceRepository;
import com.kage.notification.domain.repository.NotificationRepository;
import com.kage.notification.domain.service.NotificationChannel;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotifyPreferencesTest {

    @Mock NotificationRepository repository;
    @Mock NotificationChannel channel;
    @Mock NotificationPreferenceRepository preferences;

    private final UUID customerId = UUID.randomUUID();
    private Notify notify;

    @BeforeEach
    void setUp() {
        notify = new Notify(repository, channel, Clock.systemUTC(), preferences);
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
    void categoriaDesligada_ficaSoNaCaixaDeEntrada() {
        when(preferences.disabledFor(customerId)).thenReturn(EnumSet.of(NotificationCategory.ACCOUNT));
        when(repository.saveIfNew(any())).thenReturn(true);

        assertThat(notify.execute(input(NotificationType.DEPOSIT_RECEIVED))).isTrue();

        assertThat(saved().isPushed()).isFalse();
        verify(channel, never()).deliver(any());
    }

    @Test
    void avisoCritico_vaiPorPushMesmoComACategoriaDesligada() {
        when(preferences.disabledFor(customerId)).thenReturn(EnumSet.of(NotificationCategory.CARD_PURCHASES));
        when(repository.saveIfNew(any())).thenReturn(true);

        notify.execute(input(NotificationType.CARD_PURCHASE_DECLINED));

        assertThat(saved().isPushed()).isTrue();
        verify(channel).deliver(any());
    }

    @Test
    void categoriaLigada_vaiPorPush() {
        when(preferences.disabledFor(customerId)).thenReturn(Set.of());
        when(repository.saveIfNew(any())).thenReturn(true);

        notify.execute(input(NotificationType.PIX_RECEIVED));

        assertThat(saved().isPushed()).isTrue();
        verify(channel).deliver(any());
    }

    @Test
    void regraDoPush() {
        Set<NotificationCategory> everything = EnumSet.allOf(NotificationCategory.class);
        // obrigatórias e críticos ignoram o que estiver desligado
        assertThat(NotificationType.INVOICE_CLOSED.shouldPush(everything)).isTrue();
        assertThat(NotificationType.NEW_DEVICE_LOGIN.shouldPush(everything)).isTrue();
        assertThat(NotificationType.CARD_DISPUTE_RESOLVED.shouldPush(everything)).isTrue();
        assertThat(NotificationType.PIX_FAILED.shouldPush(everything)).isTrue();
        assertThat(NotificationType.PIX_AUTOMATIC_REQUESTED.shouldPush(everything)).isTrue();
        // as outras obedecem
        assertThat(NotificationType.PIX_SENT.shouldPush(everything)).isFalse();
        assertThat(NotificationType.CARD_PURCHASE_APPROVED.shouldPush(everything)).isFalse();
        assertThat(NotificationType.SAVINGS_GOAL_REACHED.shouldPush(everything)).isFalse();
        assertThat(NotificationType.SAVINGS_GOAL_REACHED.shouldPush(Set.of())).isTrue();
    }

    @Test
    void preferencias_naoDesligaObrigatoria_eValidaEntrada() {
        NotificationPreferences useCase = new NotificationPreferences(preferences, Clock.systemUTC());

        assertThatThrownBy(() -> useCase.set(customerId, "invoice", false))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não podem ser desligados");
        assertThatThrownBy(() -> useCase.set(customerId, "MARKETING", false)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> useCase.set(customerId, "PIX", null)).isInstanceOf(ValidationException.class);

        when(preferences.disabledFor(customerId)).thenReturn(EnumSet.of(NotificationCategory.PIX));
        var list = useCase.set(customerId, "pix", false);

        verify(preferences).set(eq(customerId), eq(NotificationCategory.PIX), eq(false), any());
        assertThat(list).hasSize(NotificationCategory.values().length);
        assertThat(list).filteredOn(p -> p.category().equals("PIX")).singleElement()
                .satisfies(p -> assertThat(p.enabled()).isFalse());
        assertThat(list).filteredOn(p -> p.category().equals("INVOICE")).singleElement()
                .satisfies(p -> { assertThat(p.enabled()).isTrue(); assertThat(p.mandatory()).isTrue(); });
    }
}
