package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.CategoryBudgets;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orçamento por categoria com o fluxo real (compra → outbox → RabbitMQ → caixa de entrada). Cartão de
 * R$ 10.000 que fecha dia 10 e vence dia 20; março/2034.
 */
class CategoryBudgetIntegrationTest extends AbstractIntegrationTest {

    private static final String TYPE = "CARD_BUDGET_ALERT";

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CategoryBudgets categoryBudgets;
    @Autowired ListNotifications listNotifications;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID cardId) {}

    private Setup card() {
        today(LocalDate.of(2034, 3, 1));
        IssueCard.Input input = issueCardInput(new BigDecimal("10000.00"), 10, 20);
        return new Setup(input.customerId(), issueCard.execute(input).cardId());
    }

    private void buy(UUID cardId, String amount, String mcc, LocalDateTime at, int installments) {
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Compra", new BigDecimal(amount),
                at, installments, null, null, null, null, mcc));
    }

    private void buy(UUID cardId, String amount, String mcc) {
        buy(cardId, amount, mcc, LocalDateTime.of(2034, 3, 2, 12, 0), 1);
    }

    private List<ListNotifications.Item> alerts(UUID customerId) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(TYPE)).toList();
    }

    private void await(UUID customerId, int count) {
        awaitUntil(() -> alerts(customerId).size() >= count, Duration.ofSeconds(15));
    }

    private CategoryBudgets.Item budget(UUID cardId, String category) {
        return categoryBudgets.list(cardId, null).budgets().stream().filter(b -> b.category().equals(category))
                .findFirst().orElseThrow();
    }

    @Test
    void avisaAos80_eAos100_umaVezCada_soDaCategoriaComOrcamento() {
        Setup s = card();
        CategoryBudgets.Item created = categoryBudgets.set(s.cardId(), "supermarket", new BigDecimal("1000.00"));
        assertThat(created.label()).isEqualTo("Mercado");
        assertThat(created.status()).isEqualTo("OK");
        assertThat(created.spent()).isEqualByComparingTo("0");

        buy(s.cardId(), "500.00", "5411"); // 50%: nada
        buy(s.cardId(), "350.00", "5411"); // 85%
        await(s.customerId(), 1);
        assertThat(alerts(s.customerId())).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("Você já usou 85% do orçamento de Mercado");
            assertThat(n.message()).isEqualTo("R$ 850,00 de R$ 1.000,00 em Mercado na fatura de 03/2034. Restam R$ 150,00.");
        });
        assertThat(budget(s.cardId(), "SUPERMARKET")).satisfies(b -> {
            assertThat(b.status()).isEqualTo("WARNING");
            assertThat(b.usagePercent()).isEqualTo(85);
            assertThat(b.remaining()).isEqualByComparingTo("150.00");
        });

        buy(s.cardId(), "900.00", "5812"); // restaurante, sem orçamento: nada
        buy(s.cardId(), "200.00", "5411"); // 105%
        await(s.customerId(), 2);
        buy(s.cardId(), "10.00", "5411"); // 106%: os 100% já foram avisados nesta fatura
        sleep(Duration.ofMillis(500));
        assertThat(alerts(s.customerId())).hasSize(2).anySatisfy(n -> {
            assertThat(n.title()).isEqualTo("Orçamento de Mercado estourado");
            assertThat(n.message()).isEqualTo("R$ 1.050,00 em Mercado na fatura de 03/2034: R$ 50,00 acima do orçamento de R$ 1.000,00.");
        });

        CategoryBudgets.Output march = categoryBudgets.list(s.cardId(), "2034-03");
        assertThat(march.budgets()).singleElement().satisfies(b -> {
            assertThat(b.status()).isEqualTo("EXCEEDED");
            assertThat(b.spent()).isEqualByComparingTo("1060.00");
            assertThat(b.remaining()).isEqualByComparingTo("-60.00");
        });
        assertThat(march.totalBudget()).isEqualByComparingTo("1000.00");

        // subir o orçamento zera as faixas: 1.660 de 2.000 avisa os 80% de novo
        assertThat(categoryBudgets.set(s.cardId(), "SUPERMARKET", new BigDecimal("2000.00")).status()).isEqualTo("OK");
        buy(s.cardId(), "600.00", "5411");
        await(s.customerId(), 3);
        assertThat(alerts(s.customerId())).anySatisfy(n ->
                assertThat(n.title()).isEqualTo("Você já usou 83% do orçamento de Mercado"));
    }

    @Test
    void compraQuePulaPara100_soEsseAviso_eAFaturaSeguinteAvisaDeNovo() {
        Setup s = card();
        categoryBudgets.set(s.cardId(), "RESTAURANTS", new BigDecimal("100.00"));
        categoryBudgets.set(s.cardId(), "SHOPPING", new BigDecimal("300.00"));

        buy(s.cardId(), "150.00", "5812"); // direto para 150%
        buy(s.cardId(), "900.00", "5732", LocalDateTime.of(2034, 3, 2, 12, 0), 3); // 300 por mês: exatamente 100%
        await(s.customerId(), 2);
        sleep(Duration.ofMillis(500));
        assertThat(alerts(s.customerId())).extracting(ListNotifications.Item::title)
                .containsExactlyInAnyOrder("Orçamento de Restaurantes e delivery estourado", "Orçamento de Compras atingido");
        assertThat(alerts(s.customerId())).anySatisfy(n -> assertThat(n.message())
                .isEqualTo("R$ 300,00 de R$ 300,00 em Compras na fatura de 03/2034."));

        // compra depois do fechamento: fatura de abril, orçamento novo
        buy(s.cardId(), "90.00", "5812", LocalDateTime.of(2034, 3, 12, 12, 0), 1);
        await(s.customerId(), 3);
        assertThat(alerts(s.customerId())).anySatisfy(n -> {
            assertThat(n.title()).isEqualTo("Você já usou 90% do orçamento de Restaurantes e delivery");
            assertThat(n.message()).contains("fatura de 04/2034");
        });
        // abril também tem a 2ª parcela do notebook (300 de 300), mas parcela não é compra nova: sem aviso
        assertThat(categoryBudgets.list(s.cardId(), "2034-04").budgets())
                .filteredOn(b -> b.category().equals("SHOPPING")).singleElement()
                .satisfies(b -> assertThat(b.status()).isEqualTo("EXCEEDED"));
    }

    @Test
    void validacoes_eRemocao() {
        Setup s = card();
        assertThatThrownBy(() -> categoryBudgets.set(s.cardId(), "SUPERMARKET", BigDecimal.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> categoryBudgets.set(s.cardId(), "SUPERMARKET", null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> categoryBudgets.set(s.cardId(), "SUPERMARKET", new BigDecimal("10.005")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> categoryBudgets.set(s.cardId(), "SUPERMARKET", new BigDecimal("1000000.01")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> categoryBudgets.set(s.cardId(), "SAQUE", BigDecimal.TEN))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> categoryBudgets.list(UUID.randomUUID(), null)).isInstanceOf(NotFoundException.class);

        categoryBudgets.set(s.cardId(), "OTHER", new BigDecimal("50.00")); // "Outros" também tem orçamento
        buy(s.cardId(), "45.00", null); // sem MCC = Outros: 90%
        assertThat(budget(s.cardId(), "OTHER").usagePercent()).isEqualTo(90);

        categoryBudgets.remove(s.cardId(), "other");
        assertThat(categoryBudgets.list(s.cardId(), null).budgets()).isEmpty();
        assertThatThrownBy(() -> categoryBudgets.remove(s.cardId(), "OTHER")).isInstanceOf(NotFoundException.class);
    }
}
