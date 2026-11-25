package com.kage.savings.domain.entity;

import com.kage.savings.domain.service.YieldPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsBoxTest {

    private static final BigDecimal CDI = new BigDecimal("0.1415");
    private static final BigDecimal DAILY = YieldPolicy.dailyRate(CDI);
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    private SavingsBox box(String goal) {
        return SavingsBox.create(UUID.randomUUID(), UUID.randomUUID(), "  Viagem  ", goal == null ? null : Money.of(goal), MONDAY);
    }

    @Test
    void dailyRateIsTheCompoundEquivalentOver252BusinessDays() {
        // (1 + d)^252 = 1 + CDI
        double compounded = Math.pow(1 + DAILY.doubleValue(), 252);
        assertThat(compounded).isCloseTo(1.1415, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DAILY).isBetween(new BigDecimal("0.000525"), new BigDecimal("0.000526"));
    }

    @Test
    void createsActiveEmptyBoxWithTrimmedName() {
        SavingsBox box = box(null);
        assertThat(box.getName()).isEqualTo("Viagem");
        assertThat(box.getBalance()).isEqualTo(Money.ZERO);
        assertThat(box.getStatus()).isEqualTo(SavingsBox.Status.ACTIVE);
        assertThat(box.getLastYieldDate()).isEqualTo(MONDAY);
    }

    @Test
    void rejectsInvalidNameAndGoal() {
        assertThatThrownBy(() -> SavingsBox.create(UUID.randomUUID(), UUID.randomUUID(), " ", null, MONDAY))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> SavingsBox.create(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(61), null, MONDAY))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> box("0")).isInstanceOf(ValidationException.class);
    }

    @Test
    void oneBusinessDayCreditsWholeCentsAndKeepsTheFraction() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"));

        Money credited = box.accrueYield(MONDAY.plusDays(1), DAILY); // terça

        // 1000 * 0,000525... = 0,525... -> 0,52 no saldo, o resto fica pendente
        assertThat(credited).isEqualTo(Money.of("0.52"));
        assertThat(box.getBalance()).isEqualTo(Money.of("1000.52"));
        assertThat(box.getTotalYield()).isEqualTo(Money.of("0.52"));
        assertThat(box.getPendingYield()).isBetween(new BigDecimal("0.005"), new BigDecimal("0.006"));
    }

    @Test
    void weekendDoesNotYield() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"));
        box.accrueYield(MONDAY.plusDays(4), DAILY); // até sexta: 4 dias úteis
        Money balanceFriday = box.getBalance();

        Money weekend = box.accrueYield(MONDAY.plusDays(6), DAILY); // sábado e domingo

        assertThat(weekend).isEqualTo(Money.ZERO);
        assertThat(box.getBalance()).isEqualTo(balanceFriday);
        assertThat(box.getLastYieldDate()).isEqualTo(MONDAY.plusDays(6));
    }

    @Test
    void accrualIsIdempotentForTheSameDate() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"));
        box.accrueYield(MONDAY.plusDays(1), DAILY);

        assertThat(box.accrueYield(MONDAY.plusDays(1), DAILY)).isEqualTo(Money.ZERO);
        assertThat(box.accrueYield(MONDAY, DAILY)).isEqualTo(Money.ZERO);
        assertThat(box.getBalance()).isEqualTo(Money.of("1000.52"));
    }

    @Test
    void smallBalanceStillYieldsBecauseFractionsAccumulate() {
        SavingsBox box = box(null);
        box.deposit(Money.of("10.00")); // rende ~R$ 0,005 por dia: arredondar todo dia daria zero

        box.accrueYield(MONDAY.plusDays(14), DAILY); // 10 dias úteis

        assertThat(box.getTotalYield()).isEqualTo(Money.of("0.05"));
    }

    @Test
    void oneYearOfBusinessDaysMatchesTheAnnualCdi() {
        SavingsBox box = box(null);
        box.deposit(Money.of("10000.00"));

        // 252 dias úteis a partir da segunda (sem feriados, segunda a sexta)
        LocalDate end = MONDAY;
        int businessDays = 0;
        while (businessDays < 252) {
            end = end.plusDays(1);
            if (YieldPolicy.isBusinessDay(end)) businessDays++;
        }
        box.accrueYield(end, DAILY);

        // 14,15% a.a. sobre 10.000 = 1.415,00 (com capitalização diária, sem perder frações)
        assertThat(box.getBalance().amount()).isBetween(new BigDecimal("11414.99"), new BigDecimal("11415.01"));
    }

    @Test
    void goalIsReachedOnceByDepositOrByYield() {
        SavingsBox box = box("100.00");
        assertThat(box.deposit(Money.of("99.80"))).isFalse();

        box.accrueYield(MONDAY.plusDays(14), DAILY); // 99,80 rendendo 10 dias úteis passa de 100
        assertThat(box.getGoalReachedAt()).isNotNull();

        assertThat(box.deposit(Money.of("50.00"))).isFalse(); // já tinha atingido: não avisa de novo
    }

    @Test
    void withdrawCannotExceedBalance() {
        SavingsBox box = box(null);
        box.deposit(Money.of("50.00"));

        assertThatThrownBy(() -> box.withdraw(Money.of("50.01"))).isInstanceOf(BusinessRuleException.class);
        box.withdraw(Money.of("50.00"));
        assertThat(box.getBalance()).isEqualTo(Money.ZERO);
    }

    @Test
    void closedBoxDoesNotMoveNorYield() {
        SavingsBox box = box(null);
        box.deposit(Money.of("80.00"));

        assertThat(box.close()).isEqualTo(Money.of("80.00"));
        assertThat(box.getBalance()).isEqualTo(Money.ZERO);
        assertThatThrownBy(() -> box.deposit(Money.of("1.00"))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(box::close).isInstanceOf(BusinessRuleException.class);
        assertThat(box.accrueYield(MONDAY.plusDays(10), DAILY)).isEqualTo(Money.ZERO);
    }

    @Test
    void zeroAmountIsRejected() {
        SavingsBox box = box(null);
        assertThatThrownBy(() -> box.deposit(Money.ZERO)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> box.withdraw(Money.ZERO)).isInstanceOf(ValidationException.class);
    }
}
