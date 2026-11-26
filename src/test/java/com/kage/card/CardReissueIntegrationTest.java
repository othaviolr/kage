package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CancelCard;
import com.kage.card.application.usecase.CardPlastics;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.VirtualCards;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 2ª via com Postgres real: plástico novo, mesmo cartão (limite e faturas). */
class CardReissueIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired CardPlastics cardPlastics;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired GetCard getCard;
    @Autowired CancelCard cancelCard;
    @Autowired VirtualCards virtualCards;
    @Autowired DisputePurchase disputePurchase;

    private UUID card() {
        return issueCard.execute(issueCardInput(new BigDecimal("1000.00"), 10, 20)).cardId();
    }

    private RegisterPurchase.Output buy(UUID cardId, String amount, String virtualNumber) {
        return registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Loja", new BigDecimal(amount),
                LocalDateTime.now(), 1, virtualNumber));
    }

    private UUID buy(UUID cardId, String amount) {
        UUID purchaseId = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, "Loja", new BigDecimal(amount), LocalDateTime.now()));
        return purchaseId;
    }

    private String lastDigitsOf(CardPlastics.Output plastic) {
        return plastic.maskedNumber().substring(plastic.maskedNumber().length() - 4);
    }

    @Test
    void emissao_jaTrazOPrimeiroPlasticoAtivo() {
        UUID cardId = card();

        List<CardPlastics.Output> plastics = cardPlastics.list(cardId);
        assertThat(plastics).singleElement().satisfies(p -> {
            assertThat(p.sequence()).isEqualTo(1);
            assertThat(p.status()).isEqualTo("ACTIVE");
            assertThat(p.issueReason()).isEqualTo("FIRST_ISSUE");
            assertThat(p.maskedNumber()).matches("•••• \\d{4}");
        });
    }

    @Test
    void perda_cancelaOAntigoNaHora_eONovoSoValeDepoisDeDesbloqueado() {
        UUID cardId = card();
        buy(cardId, "100.00");

        CardPlastics.ReissueOutput reissued = cardPlastics.reissue(cardId, "lost");

        assertThat(reissued.plastic().status()).isEqualTo("PENDING_ACTIVATION");
        assertThat(reissued.plastic().sequence()).isEqualTo(2);
        assertThat(reissued.previous().status()).isEqualTo("CANCELLED");
        assertThat(reissued.previous().cancelReason()).isEqualTo("LOST");
        assertThat(reissued.plastic().maskedNumber()).isNotEqualTo(reissued.previous().maskedNumber());
        assertThatThrownBy(() -> buy(cardId, "10.00"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ainda não foi desbloqueado");

        // virtual continua funcionando enquanto o plástico não chega
        VirtualCards.Details virtual = virtualCards.create(new VirtualCards.Input(cardId, "Online", null));
        buy(cardId, "20.00", virtual.number());

        assertThatThrownBy(() -> cardPlastics.activate(cardId, "0000".equals(lastDigitsOf(reissued.plastic())) ? "1111" : "0000"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não conferem");
        CardPlastics.Output activated = cardPlastics.activate(cardId, lastDigitsOf(reissued.plastic()));
        assertThat(activated.status()).isEqualTo("ACTIVE");

        buy(cardId, "10.00");
        // mesmo cartão: o limite usado veio junto
        assertThat(getCard.execute(new GetCard.Input(cardId)).availableLimit()).isEqualByComparingTo("870.00");
    }

    @Test
    void defeito_oAntigoValeAteONovoSerDesbloqueado() {
        UUID cardId = card();

        CardPlastics.ReissueOutput reissued = cardPlastics.reissue(cardId, "DAMAGED");

        assertThat(reissued.previous().status()).isEqualTo("ACTIVE");
        buy(cardId, "10.00"); // o antigo ainda funciona
        assertThatThrownBy(() -> cardPlastics.reissue(cardId, "DAMAGED"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("a caminho");

        cardPlastics.activate(cardId, lastDigitsOf(reissued.plastic()));

        List<CardPlastics.Output> plastics = cardPlastics.list(cardId);
        assertThat(plastics).hasSize(2);
        assertThat(plastics.get(0).status()).isEqualTo("ACTIVE");
        assertThat(plastics.get(1).status()).isEqualTo("CANCELLED");
        assertThat(plastics.get(1).cancelReason()).isEqualTo("REPLACED");
        buy(cardId, "10.00");
    }

    @Test
    void segundaViaPerdidaNoCaminho_morreEVemOutra() {
        UUID cardId = card();
        CardPlastics.ReissueOutput first = cardPlastics.reissue(cardId, "DAMAGED");

        CardPlastics.ReissueOutput second = cardPlastics.reissue(cardId, "STOLEN");

        assertThat(second.plastic().sequence()).isEqualTo(3);
        List<CardPlastics.Output> plastics = cardPlastics.list(cardId);
        assertThat(plastics).extracting(CardPlastics.Output::status)
                .containsExactly("PENDING_ACTIVATION", "CANCELLED", "CANCELLED");
        assertThatThrownBy(() -> cardPlastics.activate(cardId, lastDigitsOf(first.plastic())))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void cartaoBloqueadoPorFraude_voltaAFuncionarComASegundaVia() {
        UUID cardId = card();
        UUID purchaseId = buy(cardId, "300.00");
        disputePurchase.open(new DisputePurchase.Input(cardId, purchaseId, "NOT_RECOGNIZED", null));
        assertThat(getCard.execute(new GetCard.Input(cardId)).status()).isEqualTo("BLOCKED");

        CardPlastics.ReissueOutput reissued = cardPlastics.reissue(cardId, "FRAUD");

        assertThat(reissued.cardUnblocked()).isTrue();
        assertThat(getCard.execute(new GetCard.Input(cardId)).status()).isEqualTo("ACTIVE");
    }

    @Test
    void validacoes_eCancelamento() {
        UUID cardId = card();
        assertThatThrownBy(() -> cardPlastics.reissue(cardId, "ESQUECI")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cardPlastics.reissue(cardId, "REPLACED")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> cardPlastics.activate(cardId, "1234"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("Não há cartão novo");

        cardPlastics.reissue(cardId, "LOST");
        cancelCard.execute(new CancelCard.Input(cardId));

        assertThat(cardPlastics.list(cardId)).allSatisfy(p -> assertThat(p.status()).isEqualTo("CANCELLED"));
        assertThat(cardPlastics.list(cardId).get(0).cancelReason()).isEqualTo("CARD_CANCELLED");
        assertThatThrownBy(() -> cardPlastics.reissue(cardId, "LOST"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cancelado");
    }
}
