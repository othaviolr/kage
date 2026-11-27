package com.kage.debt;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.card.application.usecase.CloseInvoice;
import com.kage.card.application.usecase.FinanceInvoice;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.debt.application.MyDebts;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Minhas dívidas" com Postgres real. Cartão que fecha dia 10 e vence dia 20; janeiro/2034: notebook
 * de 1.200 em 3x e mercado de 600; a fatura de janeiro (1.000) é parcelada em 3x; em fevereiro, mais
 * 300; e um empréstimo de 3.000 em 6x.
 */
class MyDebtsIntegrationTest extends AbstractIntegrationTest {

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired CloseInvoice closeInvoice;
    @Autowired FinanceInvoice financeInvoice;
    @Autowired GetCard getCard;
    @Autowired DepositAccount depositAccount;
    @Autowired PersonalLoans personalLoans;
    @Autowired MyDebts myDebts;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private record Setup(UUID customerId, UUID accountId, UUID cardId) {}

    private Setup customerWithCard() {
        today(LocalDate.of(2034, 1, 5));
        IssueCard.Input input = issueCardInput(new BigDecimal("5000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        return new Setup(input.customerId(), input.accountId(), cardId);
    }

    private void buy(UUID cardId, String description, String amount, int installments, LocalDateTime at) {
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), description, new BigDecimal(amount),
                at, installments));
    }

    private static MyDebts.Item only(MyDebts.Output out, String kind) {
        return out.debts().stream().filter(d -> d.kind().equals(kind)).findFirst().orElseThrow();
    }

    @Test
    void cartaoParceladoEEmprestimo_cadaRealUmaVez_doMaisCaroAoMaisBarato() {
        Setup s = customerWithCard();
        buy(s.cardId(), "Notebook", "1200.00", 3, LocalDateTime.of(2034, 1, 5, 10, 0));
        buy(s.cardId(), "Mercado", "600.00", 1, LocalDateTime.of(2034, 1, 6, 10, 0));
        today(LocalDate.of(2034, 1, 11));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-01"));
        financeInvoice.execute(s.cardId(), "2034-01", 3);
        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("6000.00")));
        PersonalLoans.Output loan = personalLoans.contract(s.accountId(), new BigDecimal("3000.00"), 6, 10);
        today(LocalDate.of(2034, 1, 15));
        buy(s.cardId(), "Farmácia", "300.00", 1, LocalDateTime.of(2034, 1, 15, 10, 0)); // fatura de fevereiro

        MyDebts.Output out = myDebts.execute(s.customerId());

        // fatura de janeiro parcelada: não aparece como fatura, só o parcelamento
        assertThat(out.debts()).extracting(MyDebts.Item::kind).containsExactly(
                "CARD_INVOICE_FINANCING", "LOAN", "CARD_CURRENT_INVOICE", "CARD_PURCHASE_INSTALLMENTS");

        // o que o cartão mostra soma exatamente o limite em uso
        BigDecimal card = out.debts().stream().filter(d -> s.cardId().equals(d.cardId()))
                .map(MyDebts.Item::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(card).isEqualByComparingTo(getCard.execute(new GetCard.Input(s.cardId())).usedLimit());

        MyDebts.Item financing = only(out, "CARD_INVOICE_FINANCING");
        assertThat(financing.remainingInstallments()).isEqualTo(2); // a 1ª está na fatura atual
        assertThat(financing.monthlyRate()).isEqualByComparingTo("0.0899");
        assertThat(financing.cetYearly()).isNotNull();
        assertThat(financing.payoffToday()).isLessThan(financing.outstanding());
        assertThat(financing.savingsIfPaidToday()).isPositive();

        MyDebts.Item current = only(out, "CARD_CURRENT_INVOICE");
        assertThat(current.description()).isEqualTo("Fatura atual (02/2034)");
        assertThat(current.nextDueDate()).isEqualTo(LocalDate.of(2034, 2, 20));

        MyDebts.Item notebook = only(out, "CARD_PURCHASE_INSTALLMENTS");
        assertThat(notebook.description()).isEqualTo("Notebook");
        assertThat(notebook.outstanding()).isEqualByComparingTo("400.00"); // 3/3, em março
        assertThat(notebook.payoffToday()).isEqualByComparingTo("400.00"); // sem juros: sem desconto
        assertThat(notebook.monthlyRate()).isEqualByComparingTo("0");

        MyDebts.Item loanDebt = only(out, "LOAN");
        assertThat(loanDebt.referenceId()).isEqualTo(loan.loanId());
        assertThat(loanDebt.remainingInstallments()).isEqualTo(6);
        assertThat(loanDebt.cetYearly()).isEqualByComparingTo(loan.cetYearly());
        assertThat(loanDebt.payoffToday()).isLessThan(loanDebt.outstanding());
        assertThat(loanDebt.note()).startsWith("6 parcelas a 3,99% a.m. (CET de ");

        assertThat(out.totals().outstanding()).isEqualByComparingTo(card.add(loanDebt.outstanding()));
        assertThat(out.totals().withInterest()).isEqualByComparingTo(financing.outstanding().add(loanDebt.outstanding()));
        assertThat(out.advice()).startsWith("A mais cara é parcelamento da fatura de 01/2034, a 8,99% a.m.");
    }

    @Test
    void faturaVencida_vemPrimeiro() {
        Setup s = customerWithCard();
        buy(s.cardId(), "Mercado", "500.00", 1, LocalDateTime.of(2034, 1, 6, 10, 0));
        today(LocalDate.of(2034, 1, 11));
        closeInvoice.execute(new CloseInvoice.Input(s.cardId(), "2034-01"));
        today(LocalDate.of(2034, 1, 25)); // venceu dia 20; o rotativo só entra no próximo fechamento

        MyDebts.Output out = myDebts.execute(s.customerId());

        assertThat(out.debts()).first().satisfies(d -> {
            assertThat(d.kind()).isEqualTo("CARD_INVOICE");
            assertThat(d.overdue()).isTrue();
            assertThat(d.outstanding()).isEqualByComparingTo("500.00");
            assertThat(d.note()).startsWith("Venceu em 20/01");
        });
        assertThat(out.advice()).isEqualTo("Comece pelo que está vencido: Fatura de 01/2034. Atraso gera multa e juros de mora.");
    }

    @Test
    void semDividas_eClienteInexistente() {
        Setup s = customerWithCard();
        assertThat(myDebts.execute(s.customerId()).advice()).isEqualTo("Você não deve nada no Kage.");
        assertThatThrownBy(() -> myDebts.execute(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }
}
