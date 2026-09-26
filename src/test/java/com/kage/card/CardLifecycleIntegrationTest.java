package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.BlockCard;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.UnblockCard;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo completo do contexto Card com Postgres real: IssueCard cria o cartão -> RegisterPurchase
 * autoriza compras e abre/reaproveita a fatura do mês de referência -> BlockCard/UnblockCard
 * controlam se novas compras são aceitas. Cobre o roteamento pra fatura seguinte quando a compra
 * acontece depois do fechamento, que é a regra mais fácil de quebrar silenciosamente numa
 * migração de schema ou num mapeamento incorreto.
 */
class CardLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    IssueCard issueCard;

    @Autowired
    GetCard getCard;

    @Autowired
    BlockCard blockCard;

    @Autowired
    UnblockCard unblockCard;

    @Autowired
    RegisterPurchase registerPurchase;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    InvoiceRepository invoiceRepository;

    @Test
    void execute_deveAutorizarCompraEAtualizarLimiteDisponivel_quandoCartaoAtivo() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        var purchase = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"),
                LocalDateTime.of(2026, 9, 5, 12, 0)));

        assertThat(purchase.duplicate()).isFalse();
        assertThat(purchase.availableLimit()).isEqualByComparingTo("850.00");

        var cardAfter = getCard.execute(new GetCard.Input(card.cardId()));
        assertThat(cardAfter.usedLimit()).isEqualByComparingTo("150.00");
        assertThat(cardAfter.availableLimit()).isEqualByComparingTo("850.00");

        var invoiceAfter = invoiceRepository.findById(purchase.invoiceId()).orElseThrow();
        assertThat(invoiceAfter.total().amount()).isEqualByComparingTo("150.00");
        assertThat(invoiceAfter.getItems()).hasSize(1);
    }

    @Test
    void execute_deveConsolidarComprasNaMesmaFatura_quandoAntesDoFechamento() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        var first = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"),
                LocalDateTime.of(2026, 9, 3, 10, 0)));
        var second = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Farmácia", new BigDecimal("30.00"),
                LocalDateTime.of(2026, 9, 8, 15, 0)));

        assertThat(second.invoiceId()).isEqualTo(first.invoiceId());
        assertThat(second.invoiceTotal()).isEqualByComparingTo("180.00");

        var invoice = invoiceRepository.findById(first.invoiceId()).orElseThrow();
        assertThat(invoice.getItems()).hasSize(2);
    }

    @Test
    void execute_deveAbrirFaturaSeguinte_quandoCompraAposFechamento() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        var beforeClosing = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra do mês", new BigDecimal("100.00"),
                LocalDateTime.of(2026, 9, 8, 10, 0)));
        var afterClosing = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra tardia", new BigDecimal("50.00"),
                LocalDateTime.of(2026, 9, 15, 10, 0))); // fechamento é dia 10

        assertThat(afterClosing.invoiceId()).isNotEqualTo(beforeClosing.invoiceId());
        assertThat(beforeClosing.referenceMonth()).isEqualTo(YearMonth.of(2026, 9).toString());
        assertThat(afterClosing.referenceMonth()).isEqualTo(YearMonth.of(2026, 10).toString());
    }

    @Test
    void block_deveImpedirNovasCompras_eUnblock_deveLiberarNovamente() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        blockCard.execute(new BlockCard.Input(card.cardId()));
        assertThat(getCard.execute(new GetCard.Input(card.cardId())).status()).isEqualTo("BLOCKED");

        assertThatThrownBy(() -> registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra bloqueada", new BigDecimal("10.00"),
                LocalDateTime.of(2026, 9, 5, 10, 0))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");

        unblockCard.execute(new UnblockCard.Input(card.cardId()));
        assertThat(getCard.execute(new GetCard.Input(card.cardId())).status()).isEqualTo("ACTIVE");

        var purchase = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra liberada", new BigDecimal("10.00"),
                LocalDateTime.of(2026, 9, 5, 10, 0)));
        assertThat(purchase.duplicate()).isFalse();

        var cardAfter = cardRepository.findById(card.cardId()).orElseThrow();
        assertThat(cardAfter.getUsedLimit().amount()).isEqualByComparingTo("10.00");
    }
}
