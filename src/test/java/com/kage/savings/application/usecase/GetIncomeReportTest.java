package com.kage.savings.application.usecase;

import com.kage.savings.application.query.IncomeReportSource;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetIncomeReportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final UUID CUSTOMER = UUID.randomUUID();

    /** Fonte em memória que registra o período pedido. */
    private static class FakeSource implements IncomeReportSource {
        LocalDateTime from, to;
        List<LoanFigures> loans = List.of();

        @Override
        public Optional<Holder> holder(UUID customerId) {
            return customerId.equals(CUSTOMER) ? Optional.of(new Holder("Ana", "12345678909")) : Optional.empty();
        }

        @Override
        public List<AccountBalances> accounts(UUID customerId, LocalDateTime from, LocalDateTime to) {
            this.from = from;
            this.to = to;
            return List.of(new AccountBalances(UUID.randomUUID(), "0001", "10000001-5", "CHECKING",
                    new BigDecimal("100.00"), new BigDecimal("250.00")));
        }

        @Override
        public List<BoxFigures> savingsBoxes(UUID customerId, LocalDateTime from, LocalDateTime to) {
            return List.of(
                    new BoxFigures(UUID.randomUUID(), "A", "ACTIVE", new BigDecimal("0.00"), new BigDecimal("500.00"),
                            new BigDecimal("40.00"), new BigDecimal("0.00"), new BigDecimal("8.00")),
                    new BoxFigures(UUID.randomUUID(), "B", "CLOSED", new BigDecimal("300.00"), new BigDecimal("0.00"),
                            new BigDecimal("10.00"), new BigDecimal("3.00"), new BigDecimal("1.57")));
        }

        @Override
        public List<LoanFigures> loans(UUID customerId, LocalDateTime from, LocalDateTime to) {
            return loans;
        }
    }

    private static IncomeReportSource.LoanFigures loan(String status, String before, String after, String paid) {
        return new IncomeReportSource.LoanFigures(UUID.randomUUID(), LocalDate.of(2024, 6, 3), status,
                new BigDecimal("12180.00"), 24, new BigDecimal("787.51"), new BigDecimal(before), new BigDecimal(after),
                new BigDecimal(paid));
    }

    @Test
    void loansGoToDebtsWithBalancesAndWhatWasPaid_onlyAbove5000MustBeDeclared() {
        FakeSource source = new FakeSource();
        source.loans = List.of(
                loan("ACTIVE", "10100.00", "5000.01", "9450.12"),   // passa de 5 mil em 31/12
                loan("ACTIVE", "0.00", "5000.00", "800.00"),        // exatamente 5 mil: dispensada
                loan("PAID", "6200.00", "0.00", "6000.00"));        // declarada ano passado: entra zerada para dar baixa
        var out = new GetIncomeReport(source, CLOCK).execute(new GetIncomeReport.Input(CUSTOMER, 2025));

        assertThat(out.debts()).extracting(GetIncomeReport.DebtLine::mustDeclare).containsExactly(true, false, true);
        assertThat(out.debts().get(0).description()).isEqualTo(
                "Empréstimo pessoal contratado no Kage em 03/06/2024: R$ 12.180,00 financiados (IOF incluso) em 24 parcelas de R$ 787,51.");
        assertThat(out.debts().get(2).description()).endsWith("Quitado.");
        assertThat(out.totals().debtYearEnd()).isEqualByComparingTo("10000.01");
        assertThat(out.totals().debtPaidInYear()).isEqualByComparingTo("16250.12");
        assertThat(out.guide().debts()).contains("código 11").contains("2 empréstimos precisam ser declarados.");
    }

    @Test
    void withoutLoansTheGuideSaysThereIsNothingToDeclare() {
        var out = new GetIncomeReport(new FakeSource(), CLOCK).execute(new GetIncomeReport.Input(CUSTOMER, 2025));

        assertThat(out.debts()).isEmpty();
        assertThat(out.totals().debtYearEnd()).isEqualByComparingTo("0.00");
        assertThat(out.guide().debts()).contains("nada a informar");
    }

    @Test
    void closedYearUsesDecember31CutoffsAndSumsTheWithheldTaxes() {
        FakeSource source = new FakeSource();
        var out = new GetIncomeReport(source, CLOCK).execute(new GetIncomeReport.Input(CUSTOMER, 2025));

        assertThat(out.partial()).isFalse();
        assertThat(source.from).isEqualTo(LocalDate.of(2025, 1, 1).atStartOfDay());
        assertThat(source.to).isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay());
        assertThat(out.previousYearEnd()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(out.yearEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(out.holderCpf()).isEqualTo("123.456.789-09");

        assertThat(out.savingsBoxes().get(0).netIncome()).isEqualByComparingTo("32.00");
        assertThat(out.savingsBoxes().get(1).netIncome()).isEqualByComparingTo("5.43");
        assertThat(out.totals().realizedGain()).isEqualByComparingTo("50.00");
        assertThat(out.totals().iofWithheld()).isEqualByComparingTo("3.00");
        assertThat(out.totals().incomeTaxWithheld()).isEqualByComparingTo("9.57");
        assertThat(out.totals().exclusiveTaxationIncome()).isEqualByComparingTo("37.43");
        assertThat(out.guide().exclusiveTaxation()).contains("R$ 37,43").contains("R$ 9,57");
    }

    @Test
    void currentYearIsPartialUpToNow() {
        FakeSource source = new FakeSource();
        var out = new GetIncomeReport(source, CLOCK).execute(new GetIncomeReport.Input(CUSTOMER, 2026));

        assertThat(out.partial()).isTrue();
        assertThat(out.yearEnd()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(source.to).isEqualTo(LocalDateTime.now(CLOCK));
    }

    @Test
    void rejectsYearsOutOfRangeAndUnknownCustomer() {
        GetIncomeReport report = new GetIncomeReport(new FakeSource(), CLOCK);
        assertThatThrownBy(() -> report.execute(new GetIncomeReport.Input(CUSTOMER, 1999))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> report.execute(new GetIncomeReport.Input(CUSTOMER, 2027))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> report.execute(new GetIncomeReport.Input(UUID.randomUUID(), 2025))).isInstanceOf(NotFoundException.class);
    }
}
