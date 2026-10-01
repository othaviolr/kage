package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.ListInvoices;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consulta de faturas com Postgres real: valida que o mapeamento (referenceMonth como texto, itens
 * em tabela separada) e a query derivada do findByCardId (ordenada do mês mais recente pro mais
 * antigo) funcionam de ponta a ponta, coisa que os testes com Mockito não alcançam.
 */
class CardInvoiceQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    IssueCard issueCard;

    @Autowired
    RegisterPurchase registerPurchase;

    @Autowired
    GetInvoice getInvoice;

    @Autowired
    ListInvoices listInvoices;

    @Test
    void getInvoice_deveRetornarFaturaComItens_quandoHaComprasNoMes() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        var first = registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"),
                LocalDateTime.of(2026, 9, 3, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Farmácia", new BigDecimal("30.00"),
                LocalDateTime.of(2026, 9, 8, 15, 0)));

        GetInvoice.Output invoice = getInvoice.execute(new GetInvoice.Input(card.cardId(), "2026-09"));

        assertThat(invoice.invoiceId()).isEqualTo(first.invoiceId());
        assertThat(invoice.status()).isEqualTo("OPEN");
        assertThat(invoice.total()).isEqualByComparingTo("180.00");
        assertThat(invoice.items()).hasSize(2);
        assertThat(invoice.items()).extracting(GetInvoice.ItemOutput::description)
                .containsExactlyInAnyOrder("Mercado", "Farmácia");
    }

    @Test
    void getInvoice_deveLancarNotFoundException_quandoNaoHaFaturaNoMesPedido() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"),
                LocalDateTime.of(2026, 9, 3, 10, 0)));

        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(card.cardId(), "2026-08")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getInvoice_naoDeveEnxergarFaturaDeOutroCartao() {
        var cardWithPurchase = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        var otherCard = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        registerPurchase.execute(new RegisterPurchase.Input(
                cardWithPurchase.cardId(), UUID.randomUUID(), "Mercado", new BigDecimal("150.00"),
                LocalDateTime.of(2026, 9, 3, 10, 0)));

        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(otherCard.cardId(), "2026-09")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getInvoice_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(UUID.randomUUID(), "2026-09")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cartão não encontrado");
    }

    @Test
    void listInvoices_deveRetornarFaturasDoMaisRecenteParaOMaisAntigo() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));
        registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra de agosto", new BigDecimal("40.00"),
                LocalDateTime.of(2026, 8, 5, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra de setembro", new BigDecimal("100.00"),
                LocalDateTime.of(2026, 9, 8, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(
                card.cardId(), UUID.randomUUID(), "Compra tardia", new BigDecimal("50.00"),
                LocalDateTime.of(2026, 9, 15, 10, 0))); // fechamento é dia 10 -> vai pra outubro

        ListInvoices.Output output = listInvoices.execute(new ListInvoices.Input(card.cardId()));

        assertThat(output.invoices()).extracting(ListInvoices.InvoiceSummary::referenceMonth)
                .containsExactly("2026-10", "2026-09", "2026-08");
        assertThat(output.invoices()).extracting(ListInvoices.InvoiceSummary::total)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("50.00"), new BigDecimal("100.00"), new BigDecimal("40.00"));
        assertThat(output.invoices()).extracting(ListInvoices.InvoiceSummary::itemCount)
                .containsExactly(1, 1, 1);
    }

    @Test
    void listInvoices_deveRetornarListaVazia_quandoCartaoNaoTemCompras() {
        var card = issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20));

        ListInvoices.Output output = listInvoices.execute(new ListInvoices.Input(card.cardId()));

        assertThat(output.invoices()).isEmpty();
    }

    @Test
    void listInvoices_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        assertThatThrownBy(() -> listInvoices.execute(new ListInvoices.Input(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }
}
