package com.kage.boleto.domain.entity;

import com.kage.boleto.domain.service.BoletoChargePolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoletoTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    private static Boleto issue(String amount, LocalDate due, String fine, String interest) {
        return Boleto.issue(new Boleto.IssueRequest(UUID.randomUUID(), "0001", "10000042", 7, "999", Money.of(amount), due,
                fine == null ? null : new BigDecimal(fine), interest == null ? null : new BigDecimal(interest),
                "João Pagador", "123.456.789-09", "Aluguel"), TODAY);
    }

    @Test
    void freeFieldCarriesBranchAccountAndOurNumber() {
        Boleto b = issue("1000.00", TODAY.plusDays(5), "2", "1");

        assertThat(b.getCode().freeField()).isEqualTo("0001" + "10000042" + "0000000000007");
        assertThat(b.getCode().amount()).isEqualByComparingTo("1000.00");
        assertThat(b.getPayableUntil()).isEqualTo(TODAY.plusDays(5 + 59));
        assertThat(b.getPayerDocument()).isEqualTo("12345678909");
    }

    @Test
    void validatesIssue() {
        assertThatThrownBy(() -> issue("0", TODAY, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> issue("10.00", TODAY.minusDays(1), null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> issue("10.00", TODAY.plusYears(2), null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> issue("10.00", TODAY, "2.5", null)).hasMessageContaining("Multa");
        assertThatThrownBy(() -> issue("10.00", TODAY, null, "1.5")).hasMessageContaining("Juros");
    }

    @Test
    void lateChargesAreFineOncePlusInterestProRata() {
        Boleto b = issue("1000.00", TODAY, "2", "1"); // vence hoje (quarta, dia útil)

        // pago 15 dias depois: multa 2% = 20; juros 1% a.m. * 15/30 = 5
        BoletoChargePolicy.Charges c = b.chargesOn(TODAY.plusDays(15));
        assertThat(c.daysLate()).isEqualTo(15);
        assertThat(c.fine()).isEqualByComparingTo("20.00");
        assertThat(c.interest()).isEqualByComparingTo("5.00");
        assertThat(b.totalOn(TODAY.plusDays(15))).isEqualTo(Money.of("1025.00"));
        assertThat(b.totalOn(TODAY)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    void dueDateOnWeekendOrHolidayCanBePaidNextBusinessDayWithoutCharges() {
        // sábado 28/11: paga na segunda 30/11 sem encargos
        assertThat(BoletoChargePolicy.graceDate(LocalDate.of(2026, 11, 28))).isEqualTo(LocalDate.of(2026, 11, 30));
        // sexta 20/11 é feriado (Consciência Negra): segunda 23/11
        assertThat(BoletoChargePolicy.graceDate(LocalDate.of(2026, 11, 20))).isEqualTo(LocalDate.of(2026, 11, 23));

        var onGrace = BoletoChargePolicy.on(new BigDecimal("1000"), LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 30),
                new BigDecimal("2"), new BigDecimal("1"));
        assertThat(onGrace.total()).isEqualByComparingTo("0");

        // terça 01/12 já conta do vencimento original: 3 dias
        var late = BoletoChargePolicy.on(new BigDecimal("1000"), LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 1),
                new BigDecimal("2"), new BigDecimal("1"));
        assertThat(late.daysLate()).isEqualTo(3);
        assertThat(late.fine()).isEqualByComparingTo("20.00");
        assertThat(late.interest()).isEqualByComparingTo("1.00");
    }

    @Test
    void paidOnceThenNoMore() {
        Boleto b = issue("80.00", TODAY.plusDays(1), null, null);
        assertThat(b.pay(TODAY, "KAGE")).isEqualTo(Money.of("80.00"));

        assertThat(b.getStatus()).isEqualTo(Boleto.Status.PAID);
        assertThatThrownBy(() -> b.pay(TODAY, "KAGE")).isInstanceOf(BusinessRuleException.class).hasMessageContaining("já foi pago");
        assertThatThrownBy(b::cancel).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void cancelledAndExpiredCannotBePaid() {
        Boleto cancelled = issue("80.00", TODAY.plusDays(1), null, null);
        cancelled.cancel();
        assertThatThrownBy(() -> cancelled.pay(TODAY, "KAGE")).hasMessageContaining("cancelado");

        Boleto b = issue("80.00", TODAY, null, null);
        assertThatThrownBy(() -> b.pay(TODAY.plusDays(60), "KAGE")).hasMessageContaining("prazo");
        assertThat(b.displayStatus(TODAY.plusDays(60))).isEqualTo("EXPIRED");
        assertThat(b.displayStatus(TODAY.plusDays(10))).isEqualTo("OVERDUE");
        assertThat(b.displayStatus(TODAY)).isEqualTo("OPEN");
    }
}
