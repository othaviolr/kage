package com.kage.notification;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.AuthorizePurchase;
import com.kage.card.application.usecase.BlockCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aviso a cada compra no cartão: aprovada (com limite disponível) ou recusada (com o motivo). */
class PurchaseNotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired AuthorizePurchase authorizePurchase;
    @Autowired BlockCard blockCard;
    @Autowired VirtualCards virtualCards;
    @Autowired ListNotifications listNotifications;

    private record Setup(UUID cardId, UUID customerId) {}

    private Setup card(String limit) {
        IssueCard.Input input = issueCardInput(new BigDecimal(limit), 10, 20);
        return new Setup(issueCard.execute(input).cardId(), input.customerId());
    }

    private RegisterPurchase.Output buy(UUID cardId, UUID purchaseId, String description, String amount, int installments,
                                        String virtualNumber) {
        return authorizePurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, description, new BigDecimal(amount),
                LocalDateTime.now(), installments, virtualNumber));
    }

    private List<ListNotifications.Item> inbox(UUID customerId, String type) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(type)).toList();
    }

    private void await(UUID customerId, String type, int count) {
        awaitUntil(() -> inbox(customerId, type).size() >= count, Duration.ofSeconds(15));
    }

    @Test
    void aprovada_avisaComValorParcelasELimite_eRetentativaNaoAvisaDeNovo() {
        Setup s = card("1000.00");
        UUID purchaseId = UUID.randomUUID();

        buy(s.cardId(), purchaseId, "Farmácia", "300.00", 3, null);
        buy(s.cardId(), purchaseId, "Farmácia", "300.00", 3, null); // retentativa da bandeira

        await(s.customerId(), "CARD_PURCHASE_APPROVED", 1);
        sleep(Duration.ofMillis(500));
        assertThat(inbox(s.customerId(), "CARD_PURCHASE_APPROVED")).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("Compra aprovada");
            assertThat(n.message()).isEqualTo("R$ 300,00 em Farmácia, em 3x de R$ 100,00. Limite disponível: R$ 700,00. "
                    + "Não reconhece? Bloqueie o cartão pelo app.");
        });
    }

    @Test
    void recusadaPorLimite_avisaComOMotivo() {
        Setup s = card("100.00");

        assertThatThrownBy(() -> buy(s.cardId(), UUID.randomUUID(), "Eletrônicos", "500.00", 1, null))
                .isInstanceOf(BusinessRuleException.class);

        await(s.customerId(), "CARD_PURCHASE_DECLINED", 1);
        assertThat(inbox(s.customerId(), "CARD_PURCHASE_DECLINED")).singleElement().satisfies(n ->
                assertThat(n.message()).isEqualTo("A compra de R$ 500,00 em Eletrônicos foi recusada: "
                        + "Limite disponível insuficiente."));
    }

    @Test
    void cartaoVirtual_apareceNoAviso_aprovadaERecusada() {
        Setup s = card("1000.00");
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(s.cardId(), "Streaming", null));

        buy(s.cardId(), UUID.randomUUID(), "Assinatura", "39.90", 1, virtual.number());
        virtualCards.block(s.cardId(), virtual.virtualCardId());
        assertThatThrownBy(() -> buy(s.cardId(), UUID.randomUUID(), "Assinatura", "39.90", 1, virtual.number()))
                .isInstanceOf(BusinessRuleException.class);

        await(s.customerId(), "CARD_PURCHASE_APPROVED", 1);
        await(s.customerId(), "CARD_PURCHASE_DECLINED", 1);
        assertThat(inbox(s.customerId(), "CARD_PURCHASE_APPROVED").get(0).message())
                .contains("no cartão virtual \"Streaming\"");
        assertThat(inbox(s.customerId(), "CARD_PURCHASE_DECLINED").get(0).message())
                .isEqualTo("A compra de R$ 39,90 em Assinatura no cartão virtual \"Streaming\" foi recusada: "
                        + "Cartão virtual está bloqueado.");
    }

    @Test
    void cartaoBloqueado_avisaARecusa_masEntradaMalformadaNao() {
        Setup s = card("1000.00");
        blockCard.execute(new BlockCard.Input(s.cardId()));

        assertThatThrownBy(() -> buy(s.cardId(), UUID.randomUUID(), "Posto", "80.00", 1, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> buy(s.cardId(), UUID.randomUUID(), "Posto", "-1", 1, null))
                .isInstanceOf(ValidationException.class);

        await(s.customerId(), "CARD_PURCHASE_DECLINED", 1);
        sleep(Duration.ofMillis(500));
        assertThat(inbox(s.customerId(), "CARD_PURCHASE_DECLINED")).singleElement().satisfies(n ->
                assertThat(n.message()).endsWith("foi recusada: Cartão não está ativo."));
    }
}
