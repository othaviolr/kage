package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.notification.application.usecase.QuietHoursSummaries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Avisos silenciados de verdade (RabbitMQ) e o resumo quando a janela acaba. */
class QuietHoursSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired NotificationPreferences notificationPreferences;
    @Autowired ListNotifications listNotifications;
    @Autowired DepositAccount depositAccount;
    @Autowired QuietHoursSummaries quietHoursSummaries;

    private List<ListNotifications.Item> inbox(UUID customerId, String type) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(type)).toList();
    }

    @Test
    void fimDaJanela_resumeOsAvisosSilenciados_umaVezSo() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime end = now.plusHours(1);
        notificationPreferences.setQuietHours(customerId, true, now.minusHours(1).format(HH_MM), end.format(HH_MM));

        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("10.00")));
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("20.00")));
        awaitUntil(() -> inbox(customerId, "DEPOSIT_RECEIVED").size() == 2, Duration.ofSeconds(15));

        // ainda dentro da janela: nada de resumo
        quietHoursSummaries.run(now.plusMinutes(5));
        assertThat(inbox(customerId, "QUIET_HOURS_SUMMARY")).isEmpty();

        assertThat(inbox(customerId, "DEPOSIT_RECEIVED")).allSatisfy(n -> assertThat(n.silencedReason()).isEqualTo("QUIET_HOURS"));

        // 10 minutos depois do fim
        QuietHoursSummaries.Output output = quietHoursSummaries.run(end.plusMinutes(10));
        assertThat(output.summariesSent()).as("rodada: %s", output).isEqualTo(1);
        quietHoursSummaries.run(end.plusMinutes(15)); // a rodada seguinte não duplica

        assertThat(inbox(customerId, "QUIET_HOURS_SUMMARY")).singleElement().satisfies(n -> {
            assertThat(n.pushed()).isTrue();
            assertThat(n.message()).contains("chegaram 2 avisos: 2 de depósitos e saques");
        });
    }
}
