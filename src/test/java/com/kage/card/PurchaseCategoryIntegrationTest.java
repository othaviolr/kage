package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CardTransactions;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PurchaseCategories;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.infrastructure.export.CardTransactionsCsv;
import com.kage.shared.domain.DomainTime;
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
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Categoria das compras com Postgres real. Abril/2034, cartão que fecha dia 10: Mercado 300 (MCC 5411),
 * Restaurante 120 (5812), Uber 30 (4121), Notebook 600 em 3x (5732) e Farmácia 50 sem MCC.
 */
class PurchaseCategoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired DisputePurchase disputePurchase;
    @Autowired PurchaseCategories purchaseCategories;
    @Autowired CardTransactions cardTransactions;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(int month, int day) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(LocalDateTime.of(2034, month, day, 10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID market, UUID restaurant, UUID notebook, UUID pharmacy) {}

    private RegisterPurchase.Output buy(UUID cardId, UUID purchaseId, String description, String amount,
                                        int installments, String mcc) {
        return registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, description, new BigDecimal(amount),
                LocalDateTime.of(2034, 4, 5, 10, 0), installments, null, null, null, null, mcc));
    }

    private Setup scenario() {
        today(4, 5);
        UUID cardId = issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();
        Setup s = new Setup(cardId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(buy(cardId, s.market(), "Mercado", "300.00", 1, "5411").category()).isEqualTo("SUPERMARKET");
        buy(cardId, s.restaurant(), "Restaurante", "120.00", 1, "5812");
        buy(cardId, UUID.randomUUID(), "Uber", "30.00", 1, " 4121 ");
        buy(cardId, s.notebook(), "Notebook", "600.00", 3, "5732");
        assertThat(buy(cardId, s.pharmacy(), "Farmácia", "50.00", 1, null).category()).isEqualTo("OTHER");
        return s;
    }

    @Test
    void resumoDoMes_porCategoria_comParticipacao_eComparadoComOAnterior() {
        Setup s = scenario();

        PurchaseCategories.SpendingOutput april = purchaseCategories.spending(s.cardId(), null); // a atual
        assertThat(april.month()).isEqualTo("2034-04");
        assertThat(april.total()).isEqualByComparingTo("700.00"); // a 1ª parcela do notebook, não os 600
        assertThat(april.previousMonth()).isEqualTo("2034-03");
        assertThat(april.previousTotal()).isEqualByComparingTo("0");
        assertThat(april.categories())
                .extracting(PurchaseCategories.Spending::category, s2 -> s2.amount().toPlainString(),
                        s2 -> s2.share().toPlainString(), PurchaseCategories.Spending::count)
                .containsExactly(tuple("SUPERMARKET", "300.00", "42.9", 1), tuple("SHOPPING", "200.00", "28.6", 1),
                        tuple("RESTAURANTS", "120.00", "17.1", 1), tuple("OTHER", "50.00", "7.1", 1),
                        tuple("TRANSPORT", "30.00", "4.3", 1));
        assertThat(april.categories().get(0).label()).isEqualTo("Mercado");

        // maio: só a 2ª parcela; o que foi gasto em abril aparece para comparar
        PurchaseCategories.SpendingOutput may = purchaseCategories.spending(s.cardId(), "2034-05");
        assertThat(may.total()).isEqualByComparingTo("200.00");
        assertThat(may.previousTotal()).isEqualByComparingTo("700.00");
        assertThat(may.categories().get(0)).satisfies(c -> {
            assertThat(c.category()).isEqualTo("SHOPPING");
            assertThat(c.share()).isEqualByComparingTo("100.0");
            assertThat(c.previousAmount()).isEqualByComparingTo("200.00");
        });
        assertThat(may.categories()).filteredOn(c -> c.category().equals("SUPERMARKET")).singleElement()
                .satisfies(c -> {
                    assertThat(c.amount()).isEqualByComparingTo("0");
                    assertThat(c.count()).isZero();
                    assertThat(c.previousAmount()).isEqualByComparingTo("300.00");
                });

        assertThatThrownBy(() -> purchaseCategories.spending(s.cardId(), "abril"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void clienteTrocaACategoria_valeParaTodasAsParcelas_eMantemOMcc() {
        Setup s = scenario();

        PurchaseCategories.PurchaseOutput pharmacy = purchaseCategories.customize(s.cardId(), s.pharmacy(), "health");
        assertThat(pharmacy.category()).isEqualTo("HEALTH");
        assertThat(pharmacy.label()).isEqualTo("Saúde");
        assertThat(pharmacy.mcc()).isNull();
        assertThat(pharmacy.customized()).isTrue();

        PurchaseCategories.PurchaseOutput notebook = purchaseCategories.customize(s.cardId(), s.notebook(), "EDUCATION");
        assertThat(notebook.mcc()).isEqualTo("5732");
        assertThat(purchaseCategories.get(s.cardId(), s.market()))
                .satisfies(m -> {
                    assertThat(m.category()).isEqualTo("SUPERMARKET");
                    assertThat(m.mcc()).isEqualTo("5411");
                    assertThat(m.customized()).isFalse();
                });

        assertThat(purchaseCategories.spending(s.cardId(), "2034-04").categories())
                .extracting(PurchaseCategories.Spending::category)
                .contains("HEALTH", "EDUCATION").doesNotContain("OTHER", "SHOPPING");
        assertThat(purchaseCategories.spending(s.cardId(), "2034-06").categories())
                .extracting(PurchaseCategories.Spending::category)
                .containsExactly("EDUCATION"); // 3ª parcela; em maio, para comparar, também já é EDUCATION

        assertThatThrownBy(() -> purchaseCategories.customize(s.cardId(), s.market(), "SAQUE"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("SUPERMARKET");
        assertThatThrownBy(() -> purchaseCategories.customize(s.cardId(), UUID.randomUUID(), "HEALTH"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> buy(s.cardId(), UUID.randomUUID(), "Loja", "10.00", 1, "54A1"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("4 dígitos");
    }

    @Test
    void extrato_mostraEFiltraPorCategoria() {
        Setup s = scenario();

        CardTransactions.Output food = cardTransactions.list(s.cardId(), "2034-04", "2034-04", null, null,
                "supermarket,RESTAURANTS");
        assertThat(food.categories()).containsExactly("SUPERMARKET", "RESTAURANTS");
        assertThat(food.lines()).extracting(CardTransactions.Line::description, CardTransactions.Line::categoryLabel)
                .containsExactlyInAnyOrder(tuple("Mercado", "Mercado"), tuple("Restaurante", "Restaurantes e delivery"));
        assertThat(food.total()).isEqualByComparingTo("420.00");

        String csv = CardTransactionsCsv.write(cardTransactions.list(s.cardId(), "2034-04", "2034-04", null, null));
        assertThat(csv).contains("05/04/2034;04/2034;Uber;;;Compras;Transporte;30,00\r\n");

        assertThatThrownBy(() -> cardTransactions.list(s.cardId(), null, null, null, null, "SAQUE"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void contestacao_saiDoGastoDaCategoria_eVoltaSeNegada() {
        Setup s = scenario();

        today(4, 6); // fatura de abril aberta: a compra contestada sai dela
        DisputePurchase.Output opened = disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.restaurant(),
                "NOT_RECEIVED", "Pedido não chegou"));
        assertThat(purchaseCategories.spending(s.cardId(), "2034-04").categories())
                .extracting(PurchaseCategories.Spending::category).doesNotContain("RESTAURANTS");

        disputePurchase.resolve(s.cardId(), opened.disputeId(), false, "Compra reconhecida pelo estabelecimento");
        assertThat(purchaseCategories.spending(s.cardId(), "2034-04").categories())
                .filteredOn(c -> c.category().equals("RESTAURANTS")).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("120.00")); // "contestação negada" é gasto

        // já cobrada em fatura fechada: o crédito da contestação abate o gasto do Mercado na fatura atual
        today(4, 11);
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-04"));
        today(4, 15);
        disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.market(), "NOT_RECEIVED", "Não entregaram"));
        CardTransactions.Output may = cardTransactions.list(s.cardId(), "2034-05", "2034-05", "CREDITS", null);
        assertThat(may.lines()).singleElement().satisfies(l -> {
            assertThat(l.kind()).isEqualTo("DISPUTE_CREDIT");
            assertThat(l.category()).isEqualTo("SUPERMARKET");
        });
        assertThat(purchaseCategories.spending(s.cardId(), "2034-05").categories())
                .filteredOn(c -> c.category().equals("SUPERMARKET")).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("-300.00"));
    }
}
