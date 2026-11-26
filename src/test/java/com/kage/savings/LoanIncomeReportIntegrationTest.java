package com.kage.savings;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.loan.application.usecase.PersonalLoans;
import com.kage.savings.application.usecase.GetIncomeReport;
import com.kage.savings.infrastructure.report.JdbcIncomeReportSource;
import com.kage.shared.domain.DomainTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empréstimo no informe de rendimentos com Postgres real: contratado em 2035, parcelas pagas até
 * dezembro, quitado em 2036. O relógio do domínio anda pelos dois anos; o informe é tirado em 2037.
 */
class LoanIncomeReportIntegrationTest extends AbstractIntegrationTest {

    @Autowired PersonalLoans personalLoans;
    @Autowired DepositAccount depositAccount;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static void today(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(date.atTime(10, 0).atZone(zone).toInstant(), zone));
    }

    private GetIncomeReport.Output report(UUID customerId, int year) {
        ZoneId zone = ZoneId.systemDefault();
        Clock june2037 = Clock.fixed(LocalDate.of(2037, 6, 1).atTime(12, 0).atZone(zone).toInstant(), zone);
        return new GetIncomeReport(new JdbcIncomeReportSource(jdbcTemplate), june2037)
                .execute(new GetIncomeReport.Input(customerId, year));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void saldoDevedorEm31_12_valorPagoNoAno_eBaixaNoAnoDaQuitacao() {
        today(LocalDate.of(2035, 3, 1));
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("30000.00"))); // renda de 10 mil

        PersonalLoans.Output loan = personalLoans.contract(accountId, new BigDecimal("12000.00"), 24, 10);
        // as 9 parcelas de 2035 (abril a dezembro), pagas no vencimento
        for (int n = 0; n < 9; n++) {
            today(loan.installments().get(n).dueDate());
            personalLoans.payNext(accountId, loan.loanId());
        }
        List<PersonalLoans.InstallmentView> paid2035 = personalLoans.get(accountId, loan.loanId()).installments().subList(0, 9);
        BigDecimal paidIn2035 = sum(paid2035.stream().map(PersonalLoans.InstallmentView::paidAmount).toList());
        // saldo devedor: o principal que a Price ainda não amortizou (parcelas 10 a 24)
        BigDecimal balance2035 = sum(loan.installments().subList(9, 24).stream().map(PersonalLoans.InstallmentView::principal).toList());
        assertThat(balance2035).isEqualByComparingTo(loan.financed()
                .subtract(sum(paid2035.stream().map(PersonalLoans.InstallmentView::principal).toList())));

        // em 2036: parcela de janeiro e quitação em fevereiro
        today(LocalDate.of(2036, 1, 10));
        BigDecimal january = personalLoans.payNext(accountId, loan.loanId()).amount();
        today(LocalDate.of(2036, 2, 15));
        BigDecimal payoff = personalLoans.payOff(accountId, loan.loanId()).amount();

        // e um que ela desiste em 7 dias: não houve dívida, não entra
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal("9000.00")));
        PersonalLoans.Output regret = personalLoans.contract(accountId, new BigDecimal("1000.00"), 6, 10);
        today(LocalDate.of(2036, 2, 20));
        personalLoans.withdraw(accountId, regret.loanId());

        // e um pequeno, contratado em março: abaixo de 5 mil, dispensado
        today(LocalDate.of(2036, 3, 1));
        PersonalLoans.Output small = personalLoans.contract(accountId, new BigDecimal("2000.00"), 6, 10);

        // informe de 2034: o empréstimo ainda não existia
        assertThat(report(customerId, 2034).debts()).isEmpty();

        // informe de 2035: saldo devedor em 31/12 acima de 5 mil — declarar
        GetIncomeReport.Output y2035 = report(customerId, 2035);
        assertThat(y2035.debts()).hasSize(1);
        GetIncomeReport.DebtLine debt = y2035.debts().get(0);
        assertThat(debt.loanId()).isEqualTo(loan.loanId());
        assertThat(debt.balancePreviousYearEnd()).isEqualByComparingTo("0.00");
        assertThat(debt.balanceYearEnd()).isEqualByComparingTo(balance2035).isGreaterThan(new BigDecimal("5000"));
        assertThat(debt.paidInYear()).isEqualByComparingTo(paidIn2035);
        assertThat(debt.mustDeclare()).isTrue();
        assertThat(debt.description()).startsWith("Empréstimo pessoal contratado no Kage em 01/03/2035:")
                .contains("em 24 parcelas de");
        assertThat(y2035.totals().debtYearEnd()).isEqualByComparingTo(balance2035);
        assertThat(y2035.guide().debts()).contains("código 11").contains("1 empréstimo precisa ser declarado.");

        // informe de 2036: o quitado entra zerado (baixa), o cancelado some, o pequeno é dispensado
        GetIncomeReport.Output y2036 = report(customerId, 2036);
        assertThat(y2036.debts()).extracting(GetIncomeReport.DebtLine::loanId).containsExactly(loan.loanId(), small.loanId());
        GetIncomeReport.DebtLine paidOff = y2036.debts().get(0);
        assertThat(paidOff.status()).isEqualTo("PAID");
        assertThat(paidOff.balancePreviousYearEnd()).isEqualByComparingTo(balance2035);
        assertThat(paidOff.balanceYearEnd()).isEqualByComparingTo("0.00");
        assertThat(paidOff.paidInYear()).isEqualByComparingTo(january.add(payoff));
        assertThat(paidOff.mustDeclare()).isTrue();
        assertThat(paidOff.description()).endsWith("Quitado.");

        GetIncomeReport.DebtLine smallDebt = y2036.debts().get(1);
        assertThat(smallDebt.balancePreviousYearEnd()).isEqualByComparingTo("0.00");
        assertThat(smallDebt.balanceYearEnd()).isEqualByComparingTo(small.financed()); // nenhuma parcela paga
        assertThat(smallDebt.paidInYear()).isEqualByComparingTo("0.00");
        assertThat(smallDebt.mustDeclare()).isFalse();
        assertThat(y2036.guide().debts()).contains("1 empréstimo precisa ser declarado.");

        // informe de 2037 (parcial): o quitado já saiu; o pequeno segue
        assertThat(report(customerId, 2037).debts()).extracting(GetIncomeReport.DebtLine::loanId).containsExactly(small.loanId());
    }
}
