package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.FinanceInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.ListInvoices;
import com.kage.card.application.usecase.PayInvoice;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.RunBillingCycle;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compra parcelada, parcelamento da fatura e o parcelamento automático da regra do Bacen, com
 * Postgres real. Cartão fecha dia 10 e vence dia 20; datas em 2034 (banco compartilhado).
 */
class InstallmentsIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired RunBillingCycle runBillingCycle;
    @Autowired PayInvoice payInvoice;
    @Autowired GetInvoice getInvoice;
    @Autowired ListInvoices listInvoices;
    @Autowired GetCard getCard;
    @Autowired FinanceInvoice financeInvoice;
    @Autowired DepositAccount depositAccount;

    private record Setup(UUID cardId, UUID accountId) {}

    private Setup card() {
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal("5000.00")));
        return new Setup(cardId, input.accountId());
    }

    private BigDecimal invoiceTotal(Setup s, String month) {
        return getInvoice.execute(new GetInvoice.Input(s.cardId(), month)).total();
    }

    @Test
    void compraParcelada_umaParcelaPorFatura_limiteConsumidoPeloTotal() {
        Setup s = card();
        UUID purchaseId = UUID.randomUUID();

        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), purchaseId,
                "Geladeira", new BigDecimal("1000.00"), LocalDateTime.of(2034, 3, 5, 10, 0), 3));

        assertThat(output.installments()).isEqualTo(3);
        assertThat(output.installmentAmount()).isEqualByComparingTo("333.34");
        assertThat(output.availableLimit()).isEqualByComparingTo("4000.00"); // total já consumido
        assertThat(invoiceTotal(s, "2034-03")).isEqualByComparingTo("333.34");
        assertThat(invoiceTotal(s, "2034-04")).isEqualByComparingTo("333.33");
        assertThat(invoiceTotal(s, "2034-05")).isEqualByComparingTo("333.33");

        var april = getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-04")).items().get(0);
        assertThat(april.description()).isEqualTo("Geladeira (2/3)");
        assertThat(april.installmentNumber()).isEqualTo(2);

        // retry da mesma compra não lança de novo (idempotência pela 1ª parcela)
        RegisterPurchase.Output retry = registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), purchaseId,
                "Geladeira", new BigDecimal("1000.00"), LocalDateTime.of(2034, 3, 5, 10, 0), 3));
        assertThat(retry.duplicate()).isTrue();
        assertThat(listInvoices.execute(new ListInvoices.Input(s.cardId())).invoices()).hasSize(3);

        // pagar a fatura de março devolve só a parcela paga
        runBillingCycle.execute(LocalDate.of(2034, 3, 11));
        payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-03"));
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("4333.34");
    }

    @Test
    void parcelarAFatura_simulaContrata_eAsParcelasVaoParaAsProximas() {
        Setup s = card();
        registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(), "Viagem",
                new BigDecimal("1000.00"), LocalDateTime.of(2034, 6, 5, 10, 0)));
        runBillingCycle.execute(LocalDate.of(2034, 6, 11)); // fecha junho

        FinanceInvoice.Output quote = financeInvoice.quote(s.cardId(), "2034-06", 6);
        assertThat(quote.status()).isEqualTo("SIMULATION");
        assertThat(quote.total()).isEqualByComparingTo("1337.10");
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-06")).status()).isEqualTo("CLOSED"); // nada mudou

        FinanceInvoice.Output plan = financeInvoice.execute(s.cardId(), "2034-06", 6);

        assertThat(plan.status()).isEqualTo("FINANCED");
        assertThat(plan.interest()).isEqualByComparingTo("337.10");
        // IOF: 0,38% de 1000 (3,80) + 0,0082% ao dia sobre cada amortização até o vencimento dela (9,39)
        assertThat(plan.iof()).isEqualByComparingTo("13.19");
        assertThat(quote.iof()).isEqualByComparingTo("13.19"); // a simulação já mostrava
        // CET: juros de 8,99% a.m. mais o IOF — acima da taxa de juros sozinha
        assertThat(plan.cetMonthly()).isGreaterThan(new BigDecimal("0.0899")).isLessThan(new BigDecimal("0.0950"));
        assertThat(plan.cetYearly()).isGreaterThan(new BigDecimal("1.8094"));
        assertThat(invoiceTotal(s, "2034-07")).isEqualByComparingTo("236.04"); // 1ª parcela + IOF
        assertThat(invoiceTotal(s, "2034-12")).isEqualByComparingTo("222.85");
        // juros e IOF consomem limite: 5000 - 1000 - 337,10 - 13,19
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("3649.71");

        assertThatThrownBy(() -> payInvoice.execute(new PayInvoice.Input(s.cardId(), "2034-06")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("parcelada");
        assertThatThrownBy(() -> financeInvoice.execute(s.cardId(), "2034-06", 6))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void regraDoBacen_rotativoNaoPagoPelaSegundaVez_eParceladoAutomaticamente() {
        Setup s = card();
        registerPurchase.execute(new RegisterPurchase.Input(s.cardId(), UUID.randomUUID(), "TV",
                new BigDecimal("1000.00"), LocalDateTime.of(2034, 9, 5, 10, 0)));
        runBillingCycle.execute(LocalDate.of(2034, 9, 11));  // fecha setembro (1000)
        runBillingCycle.execute(LocalDate.of(2034, 10, 11)); // nada pago: setembro vai para o rotativo em outubro

        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-09")).status()).isEqualTo("ROLLED_OVER");
        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-10")).status()).isEqualTo("CLOSED");

        // outubro (que já é rotativo) também não é paga: em novembro ela NÃO gira de novo, é parcelada
        runBillingCycle.execute(LocalDate.of(2034, 11, 11));

        assertThat(getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-10")).status()).isEqualTo("FINANCED");
        var november = getInvoice.execute(new GetInvoice.Input(s.cardId(), "2034-11"));
        assertThat(november.items()).anySatisfy(item -> {
            assertThat(item.kind()).isEqualTo("INVOICE_INSTALLMENT");
            assertThat(item.installmentNumber()).isEqualTo(1);
            assertThat(item.installmentCount()).isEqualTo(6);
        });
        assertThat(november.items()).noneMatch(item -> item.kind().equals("PREVIOUS_BALANCE"));
    }
}
