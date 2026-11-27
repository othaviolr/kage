package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.AuthorizePurchase;
import com.kage.card.application.usecase.CardPurchaseControls;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compra internacional com Postgres real e a cotação fixa de configuração (USD a R$ 5,50): USD 100 na
 * Amazon em janeiro/2034 sai a R$ 5,72 (com o spread de 4%) = R$ 572,00, mais R$ 20,02 de IOF.
 */
class InternationalPurchaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired AuthorizePurchase authorizePurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired CardPurchaseControls purchaseControls;
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
        today(LocalDate.of(2034, 1, 5));
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        return new Setup(input.customerId(), input.accountId(), issueCard.execute(input).cardId());
    }

    private RegisterPurchase.Input amazon(UUID cardId, UUID purchaseId, int installments) {
        return new RegisterPurchase.Input(cardId, purchaseId, "Amazon", new BigDecimal("100.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0), installments, null, "ONLINE", "US", "USD");
    }

    @Test
    void convertidaNaDataDaCompra_comIofNumaLinhaPropria_eOLimiteConsumidoComIof() {
        Setup s = card();
        UUID purchaseId = UUID.randomUUID();

        RegisterPurchase.Output out = authorizePurchase.execute(amazon(s.cardId(), purchaseId, 1));

        assertThat(out.international()).satisfies(fx -> {
            assertThat(fx.currency()).isEqualTo("USD");
            assertThat(fx.ptax()).isEqualByComparingTo("5.50");
            assertThat(fx.rate()).isEqualByComparingTo("5.72");
            assertThat(fx.brl()).isEqualByComparingTo("572.00");
            assertThat(fx.iof()).isEqualByComparingTo("20.02");
        });
        assertThat(out.installmentAmount()).isEqualByComparingTo("572.00");
        assertThat(out.availableLimit()).isEqualByComparingTo("4407.98"); // 5.000 − 572,00 − 20,02
        assertThat(out.invoiceTotal()).isEqualByComparingTo("592.02");

        GetInvoice.Output january = getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-01"));
        assertThat(january.items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("PURCHASE");
            assertThat(i.description()).isEqualTo("Amazon (USD 100,00 a R$ 5,7200)");
            assertThat(i.amount()).isEqualByComparingTo("572.00");
        }).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("IOF");
            assertThat(i.description()).isEqualTo("IOF da compra internacional (3,5%): Amazon");
            assertThat(i.amount()).isEqualByComparingTo("20.02");
        });

        // retentativa idempotente: devolve a mesma conversão, sem cobrar de novo
        RegisterPurchase.Output again = authorizePurchase.execute(amazon(s.cardId(), purchaseId, 1));
        assertThat(again.duplicate()).isTrue();
        assertThat(again.international().brl()).isEqualByComparingTo("572.00");
        assertThat(again.availableLimit()).isEqualByComparingTo("4407.98");

        // paga em dia: o cashback é de 1% da compra, não do IOF
        today(LocalDate.of(2034, 1, 11));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-01"));
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("600.00")));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-02")).items()).anySatisfy(i -> {
            assertThat(i.kind()).isEqualTo("CASHBACK");
            assertThat(i.amount()).isEqualByComparingTo("5.72");
        });
    }

    @Test
    void internacionalEAVista() {
        Setup s = card();
        assertThatThrownBy(() -> authorizePurchase.execute(amazon(s.cardId(), UUID.randomUUID(), 3)))
                .isInstanceOf(ValidationException.class).hasMessage("Compra internacional é à vista");
    }

    @Test
    void comInternacionalDesligado_recusa_eOAvisoMostraAMoedaDaCompra() {
        Setup s = card();
        purchaseControls.update(s.cardId(), null, false, null);

        assertThatThrownBy(() -> authorizePurchase.execute(amazon(s.cardId(), UUID.randomUUID(), 1)))
                .isInstanceOf(BusinessRuleException.class);

        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications()
                .stream().anyMatch(n -> n.type().equals("CARD_PURCHASE_DECLINED")), Duration.ofSeconds(15));
        assertThat(listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications().stream()
                .filter(n -> n.type().equals("CARD_PURCHASE_DECLINED")).findFirst().orElseThrow().message())
                .startsWith("A compra de USD 100,00 em Amazon foi recusada: Compras internacionais estão desligadas");
    }

    @Test
    void moedaEstrangeiraSemPais_aindaEInternacional() {
        Setup s = card();
        purchaseControls.update(s.cardId(), null, false, null);
        assertThatThrownBy(() -> authorizePurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(),
                "Hotel", new BigDecimal("80.00"), LocalDateTime.of(2034, 1, 5, 10, 0), 1, null, null, null, "EUR")))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Compras internacionais");
    }
}
