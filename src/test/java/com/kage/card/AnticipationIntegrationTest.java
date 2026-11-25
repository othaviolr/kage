package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.card.application.usecase.AnticipateInstallments;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.FinanceInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.ListInvoices;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Antecipação de parcelas com Postgres real. Cartão fecha dia 10 e vence dia 20; datas em 2035, e o
 * fechamento é feito por cartão (CloseInvoice), sem rodar o ciclo de todos.
 */
class AnticipationIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired FinanceInvoice financeInvoice;
    @Autowired AnticipateInstallments anticipateInstallments;
    @Autowired GetInvoice getInvoice;
    @Autowired ListInvoices listInvoices;
    @Autowired GetCard getCard;

    private UUID card() {
        return issueCard.execute(issueCardInput(new BigDecimal("5000.00"), 10, 20)).cardId();
    }

    private BigDecimal total(UUID cardId, String month) {
        return getInvoice.execute(new GetInvoice.Input(cardId, month)).total();
    }

    private BigDecimal availableLimit(UUID cardId) {
        return getCard.execute(new GetCard.Input(cardId)).availableLimit();
    }

    private int invoiceCount(UUID cardId) {
        return listInvoices.execute(new ListInvoices.Input(cardId)).invoices().size();
    }

    @Test
    void compraSemJuros_antecipaPeloValorDeFace_eTiraAsUltimas() {
        UUID cardId = card();
        UUID purchaseId = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, "Notebook", new BigDecimal("1200.00"),
                LocalDateTime.of(2035, 3, 5, 10, 0), 12));
        assertThat(invoiceCount(cardId)).isEqualTo(12);

        var plans = anticipateInstallments.listPlans(cardId);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).kind()).isEqualTo("PURCHASE");
        assertThat(plans.get(0).description()).isEqualTo("Notebook");
        assertThat(plans.get(0).currentInvoiceMonth()).isEqualTo("2035-03");
        assertThat(plans.get(0).futureInstallments()).hasSize(11); // a 1ª já está na fatura atual
        assertThat(plans.get(0).anticipateAllAmount()).isEqualByComparingTo("1100.00"); // sem juros, sem desconto

        AnticipateInstallments.Output quote = anticipateInstallments.quote(cardId, purchaseId, 3);
        assertThat(quote.status()).isEqualTo("SIMULATION");
        assertThat(invoiceCount(cardId)).isEqualTo(12); // simular não muda nada

        AnticipateInstallments.Output done = anticipateInstallments.execute(cardId, purchaseId, 3);

        assertThat(done.fromNumber()).isEqualTo(10);
        assertThat(done.toNumber()).isEqualTo(12);
        assertThat(done.amount()).isEqualByComparingTo("300.00");
        assertThat(done.discount()).isEqualByComparingTo("0.00");
        assertThat(total(cardId, "2035-03")).isEqualByComparingTo("400.00");
        assertThat(invoiceCount(cardId)).isEqualTo(9); // dez/35, jan e fev/36 ficaram vazias e saíram
        var march = getInvoice.execute(new GetInvoice.Input(cardId, "2035-03")).items();
        assertThat(march).extracting(GetInvoice.ItemOutput::description)
                .contains("Antecipação: Notebook (10 a 12/12)");
        assertThat(availableLimit(cardId)).isEqualByComparingTo("3800.00"); // compra sem juros: limite igual

        // o resto de uma vez: o plano some da lista
        anticipateInstallments.execute(cardId, purchaseId, 8);
        assertThat(total(cardId, "2035-03")).isEqualByComparingTo("1200.00");
        assertThat(invoiceCount(cardId)).isEqualTo(1);
        assertThat(anticipateInstallments.listPlans(cardId)).isEmpty();
    }

    @Test
    void parcelamentoDaFatura_antecipaComDescontoDosJuros_eDevolveLimite() {
        UUID cardId = card();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Viagem", new BigDecimal("1000.00"),
                LocalDateTime.of(2035, 6, 5, 10, 0)));
        closeInvoice.execute(new CloseInvoice.Input(cardId, "2035-06"));
        financeInvoice.execute(cardId, "2035-06", 6); // 6x de 222,85 de jul a dez
        assertThat(availableLimit(cardId)).isEqualByComparingTo("3662.90"); // 5000 - 1000 - 337,10 de juros

        var plan = anticipateInstallments.listPlans(cardId).get(0);
        assertThat(plan.kind()).isEqualTo("INVOICE_INSTALLMENT");
        assertThat(plan.monthlyRate()).isEqualByComparingTo("0.0899");
        assertThat(plan.currentInvoiceMonth()).isEqualTo("2035-07");
        assertThat(plan.futureInstallments()).extracting(AnticipateInstallments.FutureInstallment::presentValue)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("204.47"), new BigDecimal("187.60"), new BigDecimal("172.13"),
                        new BigDecimal("157.93"), new BigDecimal("144.90"));

        // só a última primeiro: cinco meses de desconto
        AnticipateInstallments.Output last = anticipateInstallments.execute(cardId, plan.planId(), 1);
        assertThat(last.fromNumber()).isEqualTo(6);
        assertThat(last.amount()).isEqualByComparingTo("144.90");
        assertThat(last.discount()).isEqualByComparingTo("77.95");
        assertThat(last.availableLimit()).isEqualByComparingTo("3740.85");

        // depois as outras quatro: 204,47 + 187,60 + 172,13 + 157,93
        AnticipateInstallments.Output rest = anticipateInstallments.execute(cardId, plan.planId(), 4);
        assertThat(rest.amount()).isEqualByComparingTo("722.13");
        assertThat(rest.discount()).isEqualByComparingTo("169.27");

        // julho: a parcela 1 + as duas antecipações; o desconto total (247,22) voltou para o limite
        assertThat(total(cardId, "2035-07")).isEqualByComparingTo("1089.88");
        assertThat(availableLimit(cardId)).isEqualByComparingTo("3910.12");
        assertThat(anticipateInstallments.listPlans(cardId)).isEmpty();
    }

    @Test
    void validacoes() {
        UUID cardId = card();
        UUID purchaseId = UUID.randomUUID();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, purchaseId, "Sofá", new BigDecimal("300.00"),
                LocalDateTime.of(2035, 9, 5, 10, 0), 3));

        assertThatThrownBy(() -> anticipateInstallments.quote(cardId, purchaseId, 3))
                .isInstanceOf(ValidationException.class).hasMessageContaining("de 1 a 2");
        assertThatThrownBy(() -> anticipateInstallments.quote(cardId, purchaseId, 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> anticipateInstallments.quote(cardId, UUID.randomUUID(), 1))
                .isInstanceOf(NotFoundException.class);
    }
}
