package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.RunBillingCycle;
import com.kage.card.domain.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rotativo de ponta a ponta com Postgres real e o ciclo de cobrança de verdade. Cartão fecha dia 10
 * e vence dia 20; datas em 2033 para não cruzar com outros testes (o banco é compartilhado).
 *
 * Fatura de janeiro: 1000 (mínimo 150), fecha 10/01, vence 20/01. A virada acontece no fechamento
 * de fevereiro (10/02, ciclo de 11/02).
 */
class RevolvingCreditIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired RunBillingCycle runBillingCycle;
    @Autowired PayInvoice payInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired DepositAccount depositAccount;
    @Autowired CardRepository cardRepository;

    private record Setup(UUID cardId, UUID accountId) {}

    private Setup cardWithJanuaryInvoiceOf1000() {
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal("3000.00")));
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Notebook",
                new BigDecimal("1000.00"), LocalDateTime.of(2033, 1, 5, 10, 0)));
        runBillingCycle.execute(LocalDate.of(2033, 1, 11)); // fecha janeiro
        return new Setup(cardId, input.accountId());
    }

    private GetInvoice.Output invoice(Setup s, String month) {
        return getInvoice.execute(new GetInvoice.Input(s.cardId(), month));
    }

    private BigDecimal itemOfKind(GetInvoice.Output invoice, String kind) {
        return invoice.items().stream().filter(i -> i.kind().equals(kind)).map(GetInvoice.ItemOutput::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void pagouAcimaDoMinimo_saldoVaiParaORotativoSoComJuros() {
        Setup s = cardWithJanuaryInvoiceOf1000();
        assertThat(invoice(s, "2033-01").minimumPayment()).isEqualByComparingTo("150.00");

        PayInvoice.Output partial = payInvoice.execute(new PayInvoice.Input(s.cardId(), "2033-01", new BigDecimal("400.00")));
        assertThat(partial.status()).isEqualTo("CLOSED");
        assertThat(partial.remaining()).isEqualByComparingTo("600.00");

        registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(), "Mercado",
                new BigDecimal("50.00"), LocalDateTime.of(2033, 1, 25, 10, 0))); // cai em fevereiro
        runBillingCycle.execute(LocalDate.of(2033, 2, 11));

        assertThat(invoice(s, "2033-01").status()).isEqualTo("ROLLED_OVER");
        GetInvoice.Output february = invoice(s, "2033-02");
        assertThat(february.status()).isEqualTo("CLOSED");
        assertThat(itemOfKind(february, "PREVIOUS_BALANCE")).isEqualByComparingTo("600.00");
        assertThat(itemOfKind(february, "REVOLVING_INTEREST")).isEqualByComparingTo("84.00"); // 14% de 600
        assertThat(itemOfKind(february, "LATE_FINE")).isEqualByComparingTo("0");
        // IOF: 0,38% de 600 (2,28) + 0,0082% ao dia × 31 dias, de 20/01 a 20/02 (1,5252)
        assertThat(itemOfKind(february, "IOF")).isEqualByComparingTo("3.81");
        assertThat(february.total()).isEqualByComparingTo("737.81"); // 600 + 84 + 3,81 + 50

        // juros e IOF consomem limite: 5000 - 737,81
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("4262.19");

        // quitar fevereiro encerra a dívida do rotativo e devolve o limite todo
        PayInvoice.Output paid = payInvoice.execute(new PayInvoice.Input(s.cardId(), "2033-02"));
        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.availableLimit()).isEqualByComparingTo("5000.00");
        assertThat(cardRepository.findById(s.cardId()).orElseThrow().hasRevolvingDebt()).isFalse();
    }

    @Test
    void naoPagouNada_eNaoComprouNoMesSeguinte_saldoViraComMultaEMora() {
        Setup s = cardWithJanuaryInvoiceOf1000();

        // sem compras em fevereiro: o ciclo abre a fatura de fevereiro só para levar o saldo
        runBillingCycle.execute(LocalDate.of(2033, 2, 11));

        assertThat(invoice(s, "2033-01").status()).isEqualTo("ROLLED_OVER");
        GetInvoice.Output february = invoice(s, "2033-02");
        assertThat(itemOfKind(february, "PREVIOUS_BALANCE")).isEqualByComparingTo("1000.00");
        assertThat(itemOfKind(february, "REVOLVING_INTEREST")).isEqualByComparingTo("140.00"); // 14%
        assertThat(itemOfKind(february, "LATE_FINE")).isEqualByComparingTo("20.00");          // 2%
        assertThat(itemOfKind(february, "LATE_INTEREST")).isEqualByComparingTo("7.00");        // 1% a.m. x 21/30
        // IOF: 0,38% de 1000 (3,80) + 0,0082% ao dia × 31 dias (2,542)
        assertThat(itemOfKind(february, "IOF")).isEqualByComparingTo("6.34");
        assertThat(february.total()).isEqualByComparingTo("1173.34");
        assertThat(february.status()).isEqualTo("CLOSED");

        // rodar o ciclo de novo não lança os encargos outra vez
        runBillingCycle.execute(LocalDate.of(2033, 2, 11));
        assertThat(invoice(s, "2033-02").total()).isEqualByComparingTo("1173.34");
    }
}
