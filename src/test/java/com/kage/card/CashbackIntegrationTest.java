package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.Cashbacks;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.DisputePurchase;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.DomainTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cashback com Postgres real. Janeiro/2034: Mercado 600 + Loja 400; fecha dia 10, vence dia 20.
 */
class CashbackIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired PayInvoice payInvoice;
    @Autowired DepositAccount depositAccount;
    @Autowired DisputePurchase disputePurchase;
    @Autowired GetInvoice getInvoice;
    @Autowired Cashbacks cashbacks;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID store) {}

    private Setup januaryClosed() {
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal("3000.00")));
        UUID store = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Mercado", new BigDecimal("600.00"),
                LocalDateTime.of(2034, 1, 5, 10, 0)));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, store, "Loja", new BigDecimal("400.00"),
                LocalDateTime.of(2034, 1, 6, 10, 0)));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2034-01"));
        return new Setup(cardId, store);
    }

    private BigDecimal itemsOfKind(UUID cardId, String month, String kind) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month)).items().stream()
                .filter(i -> i.kind().equals(kind)).map(GetInvoice.ItemOutput::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void pagaEmDia_umPorCentoNaFaturaSeguinte() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 18));

        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        assertThat(itemsOfKind(s.cardId(), "2034-02", "CASHBACK")).isEqualByComparingTo("10.00");
        Cashbacks.Summary summary = cashbacks.summary(s.cardId());
        assertThat(summary.earned()).isEqualByComparingTo("10.00");
        assertThat(summary.net()).isEqualByComparingTo("10.00");
        assertThat(summary.invoices()).singleElement().satisfies(i -> {
            assertThat(i.referenceMonth()).isEqualTo("2034-01");
            assertThat(i.creditedIn()).isEqualTo("2034-02");
        });
    }

    @Test
    void pagaComAtraso_semCashback() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 25));

        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        assertThat(cashbacks.summary(s.cardId()).earned()).isEqualByComparingTo("0");
    }

    @Test
    void parcialDepoisTotal_emDia_rendeQuandoQuita() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 12));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01", new BigDecimal("300.00")));
        assertThat(cashbacks.summary(s.cardId()).earned()).isEqualByComparingTo("0");

        today(LocalDate.of(2034, 1, 19));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        assertThat(cashbacks.summary(s.cardId()).earned()).isEqualByComparingTo("10.00");
    }

    @Test
    void contestadaAntesDoPagamento_ficaDeFora() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 12));
        disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.store(), "NOT_RECEIVED", "Não chegou"));

        today(LocalDate.of(2034, 1, 18));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        assertThat(itemsOfKind(s.cardId(), "2034-02", "CASHBACK")).isEqualByComparingTo("6.00"); // só os 600
    }

    @Test
    void contestadaDepoisDoCashback_procedenteEstorna_negadaMantem() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 18));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        today(LocalDate.of(2034, 1, 25));
        DisputePurchase.Output dispute = disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.store(),
                "NOT_RECEIVED", "Não chegou"));
        disputePurchase.resolve(s.cardId(), dispute.disputeId(), true, "Estabelecimento não comprovou");

        // o cashback dos 400 (4,00) volta como cobrança na fatura atual (fevereiro)
        assertThat(itemsOfKind(s.cardId(), "2034-02", "CASHBACK_REVERSAL")).isEqualByComparingTo("4.00");
        Cashbacks.Summary summary = cashbacks.summary(s.cardId());
        assertThat(summary.earned()).isEqualByComparingTo("10.00");
        assertThat(summary.reversed()).isEqualByComparingTo("4.00");
        assertThat(summary.net()).isEqualByComparingTo("6.00");
    }

    @Test
    void contestacaoNegada_naoEstorna() {
        Setup s = januaryClosed();
        today(LocalDate.of(2034, 1, 18));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        today(LocalDate.of(2034, 1, 25));
        DisputePurchase.Output dispute = disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.store(),
                "NOT_RECEIVED", "Não chegou"));
        disputePurchase.resolve(s.cardId(), dispute.disputeId(), false, "Entrega comprovada");

        assertThat(cashbacks.summary(s.cardId()).reversed()).isEqualByComparingTo("0");
    }
}
