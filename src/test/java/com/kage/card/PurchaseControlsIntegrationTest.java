package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.AuthorizePurchase;
import com.kage.card.application.usecase.CardPurchaseControls;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.notification.application.usecase.NotificationPreferences;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bloqueio por tipo de compra com o fluxo real (a recusa vira aviso, crítico). */
class PurchaseControlsIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired AuthorizePurchase authorizePurchase;
    @Autowired CardPurchaseControls purchaseControls;
    @Autowired VirtualCards virtualCards;
    @Autowired GetCard getCard;
    @Autowired ListNotifications listNotifications;
    @Autowired NotificationPreferences notificationPreferences;

    private record Setup(UUID customerId, UUID cardId) {}

    private Setup card() {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        return new Setup(input.customerId(), issueCard.execute(input).cardId());
    }

    private RegisterPurchase.Output buy(UUID cardId, String description, String virtualNumber, String channel, String country) {
        return authorizePurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), description,
                new BigDecimal("50.00"), LocalDateTime.of(2034, 1, 5, 10, 0), 1, virtualNumber, channel, country));
    }

    private BigDecimal available(UUID cardId) {
        return getCard.execute(new GetCard.Input(cardId)).availableLimit();
    }

    @Test
    void padrao_tudoLigado_eSemCanalContinuaComoAntes() {
        Setup s = card();
        CardPurchaseControls.View view = purchaseControls.get(s.cardId());
        assertThat(view.online()).isTrue();
        assertThat(view.international()).isTrue();
        assertThat(view.contactless()).isTrue();
        assertThat(view.notes()).hasSize(3);

        assertThatCode(() -> buy(s.cardId(), "Padaria", null, null, null)).doesNotThrowAnyException();
        assertThatCode(() -> buy(s.cardId(), "Loja em Lisboa", null, "CONTACTLESS", "PT")).doesNotThrowAnyException();
    }

    @Test
    void onlineDesligado_oFisicoRecusa_oVirtualPassa_eOAvisoSaiMesmoComACategoriaDesligada() {
        Setup s = card();
        notificationPreferences.set(s.customerId(), "CARD_PURCHASES", false);
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(s.cardId(), "Compras online", null));

        CardPurchaseControls.View view = purchaseControls.update(s.cardId(), false, null, null);
        assertThat(view.online()).isFalse();
        assertThat(view.notes().get(0)).isEqualTo("Compras online com o cartão físico: desligadas. Os cartões virtuais "
                + "continuam comprando online.");

        assertThatThrownBy(() -> buy(s.cardId(), "Loja online", null, "ONLINE", null))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(available(s.cardId())).isEqualByComparingTo("1000.00"); // nada foi cobrado

        assertThatCode(() -> buy(s.cardId(), "Loja online", virtual.number(), "ONLINE", null)).doesNotThrowAnyException();
        assertThatCode(() -> buy(s.cardId(), "Mercado", null, "CHIP", null)).doesNotThrowAnyException();

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications()
                .stream().anyMatch(n -> n.type().equals("CARD_PURCHASE_DECLINED")), Duration.ofSeconds(15));
        assertThat(listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications().stream()
                .filter(n -> n.type().equals("CARD_PURCHASE_DECLINED")).findFirst().orElseThrow())
                .satisfies(n -> {
                    assertThat(n.pushed()).isTrue(); // crítico: pode ser alguém usando o número vazado
                    assertThat(n.message()).isEqualTo("A compra de R$ 50,00 em Loja online foi recusada: Compras online "
                            + "com o cartão físico estão desligadas: use um cartão virtual ou ligue nas configurações do cartão.");
                });
    }

    @Test
    void internacionalDesligado_valeAtePraOVirtual() {
        Setup s = card();
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(s.cardId(), "Streaming", null));
        purchaseControls.update(s.cardId(), null, false, null);

        assertThatThrownBy(() -> buy(s.cardId(), "Streaming", virtual.number(), null, "us"))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Compras internacionais");
        assertThatThrownBy(() -> buy(s.cardId(), "Free shop", null, "CHIP", "AR")).isInstanceOf(BusinessRuleException.class);
        assertThatCode(() -> buy(s.cardId(), "Mercado", null, "CHIP", "BR")).doesNotThrowAnyException();
    }

    @Test
    void aproximacaoDesligada_inserirPassa() {
        Setup s = card();
        purchaseControls.update(s.cardId(), null, null, false);
        assertThatThrownBy(() -> buy(s.cardId(), "Café", null, "CONTACTLESS", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Compras por aproximação");
        assertThatCode(() -> buy(s.cardId(), "Café", null, "CHIP", null)).doesNotThrowAnyException();

        // religar
        assertThat(purchaseControls.update(s.cardId(), null, null, true).contactless()).isTrue();
        assertThatCode(() -> buy(s.cardId(), "Café", null, "CONTACTLESS", null)).doesNotThrowAnyException();
    }

    @Test
    void entradasInvalidas() {
        Setup s = card();
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(s.cardId(), "Online", null));
        assertThatThrownBy(() -> buy(s.cardId(), "Maquininha", virtual.number(), "CHIP", null))
                .isInstanceOf(ValidationException.class).hasMessage("Cartão virtual só compra online");
        assertThatThrownBy(() -> buy(s.cardId(), "X", null, "NFC", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> buy(s.cardId(), "X", null, null, "BRA")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> purchaseControls.update(s.cardId(), null, null, null)).isInstanceOf(ValidationException.class);
    }
}
