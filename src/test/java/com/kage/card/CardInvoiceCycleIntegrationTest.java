package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CancelCard;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ciclo da fatura e cancelamento do cartão com Postgres real: fechar a fatura, tentar lançar compra
 * em fatura fechada (a transação inteira precisa voltar, inclusive o limite do cartão) e as regras
 * de cancelamento.
 */
class CardInvoiceCycleIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime SEPTEMBER = LocalDateTime.of(2026, 9, 5, 10, 0);

    @Autowired
    IssueCard issueCard;

    @Autowired
    RegisterPurchase registerPurchase;

    @Autowired
    CloseInvoice closeInvoice;

    @Autowired
    CancelCard cancelCard;

    @Autowired
    GetInvoice getInvoice;

    @Autowired
    GetCard getCard;

    private IssueCard.Output newCard() {
        return issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
    }

    private RegisterPurchase.Output purchase(UUID cardId, String amount) {
        return registerPurchase.execute(new RegisterPurchase.Input(
                cardId, UUID.randomUUID(), "Compra", new BigDecimal(amount), SEPTEMBER));
    }

    @Test
    void closeInvoice_deveFecharFaturaEPersistirStatus() {
        var card = newCard();
        purchase(card.cardId(), "150.00");

        CloseInvoice.Output closed = closeInvoice.execute(new CloseInvoice.Input(card.cardId(), "2026-09"));

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.total()).isEqualByComparingTo("150.00");
        assertThat(getInvoice.execute(new GetInvoice.Input(card.cardId(), "2026-09")).status())
                .isEqualTo("CLOSED");
    }

    @Test
    void closeInvoice_deveLancarBusinessRuleException_quandoFechaDuasVezes() {
        var card = newCard();
        purchase(card.cardId(), "150.00");
        closeInvoice.execute(new CloseInvoice.Input(card.cardId(), "2026-09"));

        assertThatThrownBy(() -> closeInvoice.execute(new CloseInvoice.Input(card.cardId(), "2026-09")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Somente faturas abertas");
    }

    @Test
    void registerPurchase_deveRejeitarCompraEmFaturaFechadaESemConsumirLimite() {
        var card = newCard();
        purchase(card.cardId(), "150.00");
        closeInvoice.execute(new CloseInvoice.Input(card.cardId(), "2026-09"));

        assertThatThrownBy(() -> purchase(card.cardId(), "100.00"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Fatura não está aberta");

        // @Transactional desfez o authorizePurchase: só os 150.00 originais seguem consumidos
        assertThat(getCard.execute(new GetCard.Input(card.cardId())).availableLimit())
                .isEqualByComparingTo("850.00");
        assertThat(getInvoice.execute(new GetInvoice.Input(card.cardId(), "2026-09")).total())
                .isEqualByComparingTo("150.00");
    }

    @Test
    void cancelCard_deveCancelarCartaoNovoERejeitarNovasCompras() {
        var card = newCard();

        CancelCard.Output output = cancelCard.execute(new CancelCard.Input(card.cardId()));

        assertThat(output.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> purchase(card.cardId(), "10.00"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }

    @Test
    void cancelCard_deveLancarBusinessRuleException_quandoHaLimiteUtilizado() {
        var card = newCard();
        purchase(card.cardId(), "150.00");

        assertThatThrownBy(() -> cancelCard.execute(new CancelCard.Input(card.cardId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fatura em aberto");
    }

    @Test
    void cancelCard_deveLancarBusinessRuleException_quandoCancelaDuasVezes() {
        var card = newCard();
        cancelCard.execute(new CancelCard.Input(card.cardId()));

        assertThatThrownBy(() -> cancelCard.execute(new CancelCard.Input(card.cardId())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está cancelado");
    }
}
