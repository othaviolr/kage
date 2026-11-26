package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.GetStatement;
import com.kage.account.domain.repository.AccountRepository;
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
    @Autowired AccountRepository accountRepository;
    @Autowired GetStatement getStatement;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID cardId, UUID store, UUID accountId) {}

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
        return new Setup(cardId, store, input.accountId());
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

    private BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void naConta_caiComoLancamento_eAFaturaSeguinteNaoTemCredito() {
        Setup s = januaryClosed();
        Cashbacks.Summary chosen = cashbacks.chooseDestination(s.cardId(), "account");
        assertThat(chosen.destination()).isEqualTo("ACCOUNT");
        today(LocalDate.of(2034, 1, 18));

        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));

        assertThat(balance(s.accountId())).isEqualByComparingTo("2010.00"); // 3000 - 1000 da fatura + 10
        assertThat(getStatement.execute(new GetStatement.Input(s.accountId(), LocalDate.of(2034, 1, 18), LocalDate.of(2034, 1, 18)))
                .entries()).anySatisfy(e -> {
                    assertThat(e.type()).isEqualTo("CARD_CASHBACK");
                    assertThat(e.amount()).isEqualByComparingTo("10.00");
                });
        // nenhuma fatura de fevereiro foi aberta para receber o crédito
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> itemsOfKind(s.cardId(), "2034-02", "CASHBACK"))
                .isInstanceOf(com.kage.shared.domain.exception.NotFoundException.class);

        Cashbacks.Summary summary = cashbacks.summary(s.cardId());
        assertThat(summary.earned()).isEqualByComparingTo("10.00");
        assertThat(summary.toAccount()).isEqualByComparingTo("10.00");
        assertThat(summary.toInvoices()).isEqualByComparingTo("0");
        assertThat(summary.invoices()).singleElement().satisfies(i -> {
            assertThat(i.referenceMonth()).isEqualTo("2034-01");
            assertThat(i.destination()).isEqualTo("ACCOUNT");
            assertThat(i.creditedIn()).isNull();
        });
    }

    @Test
    void naConta_contestadaDepoisEProcedente_estornaNaFatura_semDebitarAConta() {
        Setup s = januaryClosed();
        cashbacks.chooseDestination(s.cardId(), "ACCOUNT");
        today(LocalDate.of(2034, 1, 18));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01"));
        BigDecimal afterCashback = balance(s.accountId());

        today(LocalDate.of(2034, 1, 25));
        DisputePurchase.Output dispute = disputePurchase.open(new DisputePurchase.Input(s.cardId(), s.store(),
                "NOT_RECEIVED", "Não chegou"));
        disputePurchase.resolve(s.cardId(), dispute.disputeId(), true, "Estabelecimento não comprovou");

        assertThat(itemsOfKind(s.cardId(), "2034-02", "CASHBACK_REVERSAL")).isEqualByComparingTo("4.00");
        assertThat(balance(s.accountId())).isEqualByComparingTo(afterCashback);
        assertThat(cashbacks.summary(s.cardId()).net()).isEqualByComparingTo("6.00");
    }

    @Test
    void trocarDeDestino_valeParaOsProximos() {
        Setup s = januaryClosed();
        cashbacks.chooseDestination(s.cardId(), "ACCOUNT");
        today(LocalDate.of(2034, 1, 18));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-01")); // janeiro: na conta

        cashbacks.chooseDestination(s.cardId(), "INVOICE");
        registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(), "Farmácia", new BigDecimal("200.00"),
                LocalDateTime.of(2034, 1, 25, 10, 0)));
        today(LocalDate.of(2034, 2, 10));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-02"));
        today(LocalDate.of(2034, 2, 15));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-02")); // fevereiro: abate março

        assertThat(itemsOfKind(s.cardId(), "2034-03", "CASHBACK")).isEqualByComparingTo("2.00");
        Cashbacks.Summary summary = cashbacks.summary(s.cardId());
        assertThat(summary.destination()).isEqualTo("INVOICE");
        assertThat(summary.toAccount()).isEqualByComparingTo("10.00");
        assertThat(summary.toInvoices()).isEqualByComparingTo("2.00");
        assertThat(summary.invoices()).extracting(Cashbacks.InvoiceCashback::referenceMonth).containsExactly("2034-02", "2034-01");
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
