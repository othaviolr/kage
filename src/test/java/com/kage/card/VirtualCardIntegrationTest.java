package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.BlockCard;
import com.kage.card.application.usecase.CancelCard;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.UnblockCard;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cartão virtual com Postgres real: mesmo limite e mesma fatura do físico, apagável sem afetá-lo. */
class VirtualCardIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2035, 3, 5, 10, 0);

    @Autowired IssueCard issueCard;
    @Autowired VirtualCards virtualCards;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired BlockCard blockCard;
    @Autowired UnblockCard unblockCard;
    @Autowired CancelCard cancelCard;
    @Autowired DisputePurchase disputePurchase;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void at(LocalDateTime when) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(when.atZone(zone).toInstant(), zone));
    }

    private UUID card() {
        return issueCard.execute(issueCardInput(new BigDecimal("1000.00"), 10, 20)).cardId();
    }

    private VirtualCards.Details create(UUID cardId, String nickname, String kind) {
        return virtualCards.create(new VirtualCards.Input(cardId, nickname, kind));
    }

    private RegisterPurchase.Output buy(UUID cardId, UUID purchaseId, String amount, String number) {
        return registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, "Loja online", new BigDecimal(amount),
                DomainTime.now(), 1, number));
    }

    @Test
    void compraComOVirtual_usaOLimiteEAFaturaDoFisico() {
        at(NOW);
        UUID cardId = card();
        VirtualCards.Details virtual = create(cardId, "Streaming", null);

        assertThat(virtual.kind()).isEqualTo("PERMANENT");
        assertThat(virtual.number()).hasSize(16).startsWith("529999");
        assertThat(virtual.cvv()).matches("\\d{3}");
        assertThat(virtual.expiry()).isEqualTo("03/40");
        // os dados batem com a consulta e a lista só mostra o final
        VirtualCards.Details again = virtualCards.details(cardId, virtual.virtualCardId());
        assertThat(again.number()).isEqualTo(virtual.number());
        assertThat(again.cvv()).isEqualTo(virtual.cvv());
        assertThat(virtualCards.list(cardId)).singleElement().satisfies(v ->
                assertThat(v.maskedNumber()).isEqualTo("•••• " + virtual.number().substring(12)));

        UUID purchaseId = UUID.randomUUID();
        RegisterPurchase.Output purchase = buy(cardId, purchaseId, "39.90", virtual.number().substring(0, 4) + " "
                + virtual.number().substring(4, 8) + " " + virtual.number().substring(8, 12) + " " + virtual.number().substring(12));

        assertThat(purchase.virtualCardId()).isEqualTo(virtual.virtualCardId());
        assertThat(getCard.execute(new GetCard.Input(cardId)).availableLimit()).isEqualByComparingTo("960.10");
        assertThat(getInvoice.execute(new GetInvoice.Input(cardId, "2035-03")).items()).singleElement()
                .satisfies(item -> assertThat(item.virtualCardId()).isEqualTo(virtual.virtualCardId()));
        // compra sem número continua sendo do físico
        assertThat(buy(cardId, UUID.randomUUID(), "10.00", null).virtualCardId()).isNull();
    }

    @Test
    void virtualBloqueadoOuApagado_naoCompra_eOFisicoSegue() {
        at(NOW);
        UUID cardId = card();
        VirtualCards.Details virtual = create(cardId, "Loja", "PERMANENT");

        virtualCards.block(cardId, virtual.virtualCardId());
        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "10.00", virtual.number()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("bloqueado");

        virtualCards.unblock(cardId, virtual.virtualCardId());
        buy(cardId, UUID.randomUUID(), "10.00", virtual.number());

        assertThat(virtualCards.delete(cardId, virtual.virtualCardId()).status()).isEqualTo("DELETED");
        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "10.00", virtual.number()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("apagado");
        assertThatThrownBy(() -> virtualCards.details(cardId, virtual.virtualCardId()))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(getCard.execute(new GetCard.Input(cardId)).status()).isEqualTo("ACTIVE");
        buy(cardId, UUID.randomUUID(), "10.00", null);
    }

    @Test
    void fisicoBloqueado_bloqueiaOsVirtuais() {
        at(NOW);
        UUID cardId = card();
        VirtualCards.Details virtual = create(cardId, "Loja", null);

        blockCard.execute(new BlockCard.Input(cardId));
        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "10.00", virtual.number()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não está ativo");
        assertThatThrownBy(() -> create(cardId, "Outro", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("bloqueado");

        unblockCard.execute(new UnblockCard.Input(cardId));
        buy(cardId, UUID.randomUUID(), "10.00", virtual.number());
    }

    @Test
    void temporario_expiraEm24h() {
        at(NOW);
        UUID cardId = card();
        VirtualCards.Details temporary = create(cardId, "Site desconhecido", "temporary");
        assertThat(temporary.expiresAt()).isEqualTo(NOW.plusHours(24));

        at(NOW.plusHours(23));
        buy(cardId, UUID.randomUUID(), "50.00", temporary.number());

        at(NOW.plusHours(25));
        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "50.00", temporary.number()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("expirado");
        assertThat(virtualCards.list(cardId)).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("EXPIRED"));
    }

    @Test
    void numeroDeOutroCartaoOuInvalido_naoCompra() {
        at(NOW);
        UUID cardId = card();
        UUID otherCard = card();
        VirtualCards.Details other = create(otherCard, "Dele", null);

        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "10.00", other.number()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> buy(cardId, UUID.randomUUID(), "10.00", "1234"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> virtualCards.details(cardId, other.virtualCardId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> create(cardId, "Loja", "ETERNO"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void limiteDe10Vivos_apagarLiberaVaga() {
        at(NOW);
        UUID cardId = card();
        UUID first = null;
        for (int i = 1; i <= VirtualCards.MAX_ACTIVE; i++) {
            UUID id = create(cardId, "Virtual " + i, null).virtualCardId();
            if (first == null) first = id;
        }
        assertThatThrownBy(() -> create(cardId, "Um a mais", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("10");

        virtualCards.delete(cardId, first);
        create(cardId, "Agora cabe", null);
    }

    @Test
    void naoReconheco_numaCompraDoVirtual_apagaSoOVirtual() {
        at(NOW);
        UUID cardId = card();
        VirtualCards.Details virtual = create(cardId, "Vazou", null);
        UUID purchaseId = UUID.randomUUID();
        buy(cardId, purchaseId, "200.00", virtual.number());

        DisputePurchase.Output dispute = disputePurchase.open(new DisputePurchase.Input(cardId, purchaseId, "NOT_RECOGNIZED", null));

        assertThat(dispute.virtualCardId()).isEqualTo(virtual.virtualCardId());
        assertThat(dispute.virtualCardDeleted()).isTrue();
        assertThat(dispute.cardBlocked()).isFalse();
        assertThat(getCard.execute(new GetCard.Input(cardId)).status()).isEqualTo("ACTIVE");
        assertThat(virtualCards.list(cardId)).singleElement().satisfies(v -> assertThat(v.status()).isEqualTo("DELETED"));
    }

    @Test
    void cancelarOFisico_apagaOsVirtuais() {
        at(NOW);
        UUID cardId = card();
        create(cardId, "A", null);
        create(cardId, "B", "TEMPORARY");

        cancelCard.execute(new CancelCard.Input(cardId));

        assertThat(virtualCards.list(cardId)).hasSize(2).allSatisfy(v -> assertThat(v.status()).isEqualTo("DELETED"));
    }
}
