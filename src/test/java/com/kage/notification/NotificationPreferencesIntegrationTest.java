package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.AuthorizePurchase;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.NotificationPreferences;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Preferências de push com o fluxo real (outbox → RabbitMQ → aviso). */
class NotificationPreferencesIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationPreferences notificationPreferences;
    @Autowired ListNotifications listNotifications;
    @Autowired DepositAccount depositAccount;
    @Autowired IssueCard issueCard;
    @Autowired AuthorizePurchase authorizePurchase;

    private List<ListNotifications.Item> inbox(UUID customerId, String type) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(type)).toList();
    }

    private void await(UUID customerId, String type, int count) {
        awaitUntil(() -> inbox(customerId, type).size() >= count, Duration.ofSeconds(15));
    }

    @Test
    void depositosDesligados_ficamSoNoApp_eReligandoVoltaOPush() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);

        notificationPreferences.set(customerId, "ACCOUNT", false);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("50.00")));
        await(customerId, "DEPOSIT_RECEIVED", 1);
        assertThat(inbox(customerId, "DEPOSIT_RECEIVED").get(0).pushed()).isFalse();

        notificationPreferences.set(customerId, "ACCOUNT", true);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("60.00")));
        await(customerId, "DEPOSIT_RECEIVED", 2);
        assertThat(inbox(customerId, "DEPOSIT_RECEIVED").get(0).pushed()).isTrue(); // o mais recente
    }

    @Test
    void comprasDesligadas_aprovadaSemPush_recusadaComPush() {
        IssueCard.Input card = issueCardInput(new BigDecimal("100.00"), 10, 20);
        UUID cardId = issueCard.execute(card).cardId();
        notificationPreferences.set(card.customerId(), "card_purchases", false);

        authorizePurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Padaria", new BigDecimal("20.00"),
                LocalDateTime.now()));
        assertThatThrownBy(() -> authorizePurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Loja",
                new BigDecimal("500.00"), LocalDateTime.now())));

        await(card.customerId(), "CARD_PURCHASE_APPROVED", 1);
        await(card.customerId(), "CARD_PURCHASE_DECLINED", 1);
        assertThat(inbox(card.customerId(), "CARD_PURCHASE_APPROVED").get(0).pushed()).isFalse();
        assertThat(inbox(card.customerId(), "CARD_PURCHASE_DECLINED").get(0).pushed()).isTrue(); // pode ser fraude
    }

    @Test
    void lista_mostraTodasAsCategorias_ligadasPorPadrao() {
        UUID customerId = newActiveCustomerId();

        List<NotificationPreferences.Preference> list = notificationPreferences.list(customerId);

        assertThat(list).hasSize(9).allSatisfy(p -> assertThat(p.enabled()).isTrue());
        assertThat(list).filteredOn(NotificationPreferences.Preference::mandatory)
                .extracting(NotificationPreferences.Preference::category)
                .containsExactlyInAnyOrder("SECURITY", "INVOICE", "CARD_SERVICES");
    }
}
