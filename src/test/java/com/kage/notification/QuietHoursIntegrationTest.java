package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.NotificationPreferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Horário silencioso com o fluxo real: a janela é montada em volta de "agora". */
class QuietHoursIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired NotificationPreferences notificationPreferences;
    @Autowired ListNotifications listNotifications;
    @Autowired DepositAccount depositAccount;

    private List<ListNotifications.Item> deposits(UUID customerId) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals("DEPOSIT_RECEIVED")).toList();
    }

    @Test
    void dentroDaJanela_depositoFicaSoNoApp_eDesligandoVoltaOPush() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        LocalTime now = LocalTime.now();

        NotificationPreferences.QuietHoursOutput quiet = notificationPreferences.setQuietHours(customerId, true,
                now.minusHours(1).format(HH_MM), now.plusHours(1).format(HH_MM));
        assertThat(quiet.activeNow()).isTrue();

        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("10.00")));
        awaitUntil(() -> deposits(customerId).size() == 1, Duration.ofSeconds(15));
        assertThat(deposits(customerId).get(0).pushed()).isFalse();
        assertThat(deposits(customerId).get(0).silencedReason()).isEqualTo("QUIET_HOURS");

        notificationPreferences.setQuietHours(customerId, false, null, null);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("20.00")));
        awaitUntil(() -> deposits(customerId).size() == 2, Duration.ofSeconds(15));
        assertThat(deposits(customerId).get(0).pushed()).isTrue();
        assertThat(deposits(customerId).get(0).silencedReason()).isNull();

        // a janela configurada continua salva, só desligada
        NotificationPreferences.QuietHoursOutput saved = notificationPreferences.quietHours(customerId);
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.start()).isEqualTo(now.minusHours(1).format(HH_MM));
    }
}
