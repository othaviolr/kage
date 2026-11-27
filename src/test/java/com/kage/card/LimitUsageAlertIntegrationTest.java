package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.CreditLimits;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.DomainTime;
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

/**
 * Alerta de uso do limite com o fluxo real (compra → outbox → RabbitMQ → caixa de entrada). Cartão
 * com R$ 3.000 aprovados e R$ 1.000 em uso; fecha dia 10, vence dia 20; março/2034.
 */
class LimitUsageAlertIntegrationTest extends AbstractIntegrationTest {

    private static final String TYPE = "CARD_LIMIT_USAGE_ALERT";

    @Autowired IssueCard issueCard;
    @Autowired CreditLimits creditLimits;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired ListNotifications listNotifications;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID accountId, UUID cardId) {}

    private Setup card() {
        today(LocalDate.of(2034, 3, 1));
        IssueCard.Input input = issueCardInput(new BigDecimal("3000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        creditLimits.adjust(cardId, new BigDecimal("1000.00"));
        return new Setup(input.customerId(), input.accountId(), cardId);
    }

    private void buy(UUID cardId, String amount, LocalDateTime at) {
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Compra", new BigDecimal(amount), at));
    }

    private List<ListNotifications.Item> alerts(UUID customerId) {
        return listNotifications.execute(new ListNotifications.Input(customerId, false)).notifications().stream()
                .filter(n -> n.type().equals(TYPE)).toList();
    }

    private void await(UUID customerId, int count) {
        awaitUntil(() -> alerts(customerId).size() >= count, Duration.ofSeconds(15));
    }

    @Test
    void avisaAos80_eAos100_umaVezCada_eDeNovoNaFaturaSeguinte() {
        Setup s = card();
        LocalDateTime march = LocalDateTime.of(2034, 3, 2, 12, 0);

        buy(s.cardId(), "700.00", march); // 70%: nada
        buy(s.cardId(), "150.00", march); // 85%
        await(s.customerId(), 1);
        assertThat(alerts(s.customerId())).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("Você já usou 85% do limite");
            assertThat(n.message()).isEqualTo("Restam R$ 150,00 de R$ 1.000,00 no seu cartão. "
                    + "Se precisar, dá para subir o limite até R$ 3.000,00 pelo app.");
        });

        buy(s.cardId(), "50.00", march); // 90%: os 80% já foram avisados nesta fatura
        buy(s.cardId(), "100.00", march); // 100%
        await(s.customerId(), 2);
        sleep(Duration.ofMillis(500));
        assertThat(alerts(s.customerId())).hasSize(2).anySatisfy(n -> {
            assertThat(n.title()).isEqualTo("Seu limite acabou");
            assertThat(n.message()).startsWith("Todo o limite de R$ 1.000,00 do seu cartão está em uso");
        });

        // paga a fatura de março: o limite volta; em abril, os 80% avisam de novo
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-03"));
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("1000.00")));
        today(LocalDate.of(2034, 3, 15));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-03"));
        buy(s.cardId(), "900.00", LocalDateTime.of(2034, 3, 15, 12, 0)); // fatura de abril
        await(s.customerId(), 3);
        // o cashback de março (R$ 10) abate a fatura de abril, mas o limite só volta quando o crédito é usado
        assertThat(alerts(s.customerId())).hasSize(3).anySatisfy(n -> assertThat(n.title()).isEqualTo("Você já usou 90% do limite"));
    }

    @Test
    void compraQueUsaTodoOLimite_soOAvisoDe100() {
        Setup s = card();
        buy(s.cardId(), "1000.00", LocalDateTime.of(2034, 3, 2, 12, 0));
        await(s.customerId(), 1);
        sleep(Duration.ofMillis(500));
        assertThat(alerts(s.customerId())).singleElement()
                .satisfies(n -> assertThat(n.title()).isEqualTo("Seu limite acabou"));
    }
}
