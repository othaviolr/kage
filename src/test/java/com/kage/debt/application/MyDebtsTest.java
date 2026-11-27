package com.kage.debt.application;

import com.kage.debt.domain.Debt;
import com.kage.debt.domain.DebtKind;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyDebtsTest {

    private final UUID customerId = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2034, 3, 1);

    @BeforeEach
    void fixClock() {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(TODAY.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Debt invoice(String remaining, LocalDate due, boolean overdue, boolean revolving) {
        return new Debt(DebtKind.CARD_INVOICE, UUID.randomUUID(), UUID.randomUUID(),
                "Fatura de 02/2034" + (revolving ? " (com rotativo)" : ""), bd(remaining), bd(remaining), due,
                bd(remaining), null, revolving ? bd("0.14") : BigDecimal.ZERO, revolving ? bd("4.8710") : null, overdue,
                bd("150.00"), null, 0);
    }

    private static Debt current(String total) {
        return new Debt(DebtKind.CARD_CURRENT_INVOICE, UUID.randomUUID(), UUID.randomUUID(), "Fatura atual (03/2034)",
                bd(total), bd(total), LocalDate.of(2034, 3, 20), bd(total), null, BigDecimal.ZERO, null, false, null,
                LocalDate.of(2034, 3, 10), 0);
    }

    private static Debt purchases() {
        return new Debt(DebtKind.CARD_PURCHASE_INSTALLMENTS, UUID.randomUUID(), UUID.randomUUID(), "Notebook",
                bd("800.00"), bd("800.00"), LocalDate.of(2034, 4, 20), bd("400.00"), 2, BigDecimal.ZERO, null, false,
                null, null, 0);
    }

    private static Debt financing() {
        return new Debt(DebtKind.CARD_INVOICE_FINANCING, UUID.randomUUID(), UUID.randomUUID(),
                "Parcelamento da fatura de 01/2034", bd("600.00"), bd("560.25"), LocalDate.of(2034, 4, 20),
                bd("300.00"), 2, bd("0.0899"), bd("1.9245"), false, null, null, 0);
    }

    private static Debt loan(boolean overdue) {
        return new Debt(DebtKind.LOAN, UUID.randomUUID(), null, "Empréstimo pessoal (6x)", bd("2500.00"),
                overdue ? bd("2540.10") : bd("2310.40"), LocalDate.of(2034, 3, 10), bd("500.00"), 5, bd("0.0399"),
                bd("0.6327"), overdue, null, null, overdue ? 1 : 0);
    }

    private MyDebts.Output run(Debt... debts) {
        return new MyDebts(List.of((c, today) -> List.of(debts)), id -> true).execute(customerId);
    }

    @Test
    void clienteInexistente_404() {
        MyDebts myDebts = new MyDebts(List.of(), id -> false);
        assertThatThrownBy(() -> myDebts.execute(customerId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void semDividas() {
        MyDebts.Output out = run();
        assertThat(out.debts()).isEmpty();
        assertThat(out.totals().outstanding()).isEqualByComparingTo("0");
        assertThat(out.advice()).isEqualTo("Você não deve nada no Kage.");
        assertThat(out.asOf()).isEqualTo(TODAY);
    }

    @Test
    void ordem_vencidoPrimeiro_depoisDoMaisCaroAoMaisBarato_eEmpateNoVencimento() {
        MyDebts.Output out = run(purchases(), loan(false), current("700.00"), financing(),
                invoice("1000.00", LocalDate.of(2034, 2, 20), true, false));
        assertThat(out.debts()).extracting(MyDebts.Item::kind).containsExactly(
                "CARD_INVOICE", "CARD_INVOICE_FINANCING", "LOAN", "CARD_CURRENT_INVOICE", "CARD_PURCHASE_INSTALLMENTS");
        assertThat(out.advice()).isEqualTo("Comece pelo que está vencido: Fatura de 02/2034. Atraso gera multa e juros de mora.");
    }

    @Test
    void totais() {
        MyDebts.Output out = run(purchases(), loan(false), current("700.00"), financing());
        assertThat(out.totals().outstanding()).isEqualByComparingTo("4600.00"); // 800 + 2500 + 700 + 600
        assertThat(out.totals().payoffToday()).isEqualByComparingTo("4370.65"); // 800 + 2310,40 + 700 + 560,25
        assertThat(out.totals().withInterest()).isEqualByComparingTo("3100.00"); // empréstimo + parcelamento
        // até 31/03: a parcela de 10/03 do empréstimo e a fatura de 20/03; as de abril ficam de fora
        assertThat(out.totals().dueSoon()).isEqualByComparingTo("1200.00");
        assertThat(out.advice()).isEqualTo("A mais cara é parcelamento da fatura de 01/2034, a 8,99% a.m. "
                + "(CET de 192,45% a.a.): se sobrar dinheiro, comece por ela.");
    }

    @Test
    void economiaEEncargos_deQuitarHoje() {
        MyDebts.Output out = run(loan(false));
        assertThat(out.debts().get(0).savingsIfPaidToday()).isEqualByComparingTo("189.60");
        assertThat(out.debts().get(0).lateCharges()).isEqualByComparingTo("0");

        MyDebts.Output late = run(loan(true));
        assertThat(late.debts().get(0).savingsIfPaidToday()).isEqualByComparingTo("0");
        assertThat(late.debts().get(0).lateCharges()).isEqualByComparingTo("40.10");
    }

    @Test
    void semJuros_dica() {
        MyDebts.Output out = run(current("700.00"), purchases());
        assertThat(out.advice()).isEqualTo("Nenhuma dívida com juros: pagando as faturas até o vencimento, continua assim.");
    }

    @Test
    void textos() {
        assertThat(MyDebts.note(invoice("1000.00", LocalDate.of(2034, 3, 20), false, false))).isEqualTo(
                "Pagando R$ 1.000,00 até 20/03, não há juros. Pagando só o mínimo (R$ 150,00), o resto vai para o "
                        + "rotativo, a 14% a.m.");
        assertThat(MyDebts.note(invoice("1000.00", LocalDate.of(2034, 2, 20), true, false))).isEqualTo(
                "Venceu em 20/02: no próximo fechamento, o que faltar vai para o rotativo (14% a.m.), com multa e mora "
                        + "se o pago ficar abaixo do mínimo de R$ 150,00.");
        assertThat(MyDebts.note(invoice("1000.00", LocalDate.of(2034, 3, 20), false, true))).isEqualTo(
                "Inclui o rotativo da fatura anterior, a 14% a.m. (CET de 487,1% a.a.). Pagando R$ 1.000,00 até 20/03, "
                        + "os juros param.");
        assertThat(MyDebts.note(current("700.00")))
                .isEqualTo("Fecha em 10/03 e vence em 20/03. Sem juros se paga até o vencimento.");
        assertThat(MyDebts.note(purchases()))
                .isEqualTo("2 parcelas sem juros; a próxima, de R$ 400,00, vem na fatura que vence em 20/04.");
        assertThat(MyDebts.note(financing())).isEqualTo("2 parcelas a 8,99% a.m. (CET de 192,45% a.a.). Antecipando "
                + "todas hoje, você paga R$ 560,25 — R$ 39,75 a menos de juros.");
        assertThat(MyDebts.note(loan(false))).isEqualTo("5 parcelas a 3,99% a.m. (CET de 63,27% a.a.); a próxima, de "
                + "R$ 500,00, vence em 10/03. Quitando hoje, você paga R$ 2.310,40 — R$ 189,60 de desconto nos juros.");
        assertThat(MyDebts.note(loan(true))).isEqualTo("1 parcela vencida, com multa de 2% e mora de 1% a.m. Para "
                + "quitar tudo hoje: R$ 2.540,10. Dá para renegociar pelo app.");
    }
}
