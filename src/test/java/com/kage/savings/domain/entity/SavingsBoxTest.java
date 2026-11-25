package com.kage.savings.domain.entity;

import com.kage.savings.domain.service.BusinessCalendar;
import com.kage.savings.domain.service.YieldPolicy;
import com.kage.savings.domain.valueobject.CdiSchedule;
import com.kage.savings.infrastructure.cdi.FixedCdiRateProvider;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavingsBoxTest {

    private static final BigDecimal CDI = new BigDecimal("0.1415");
    private static final FixedCdiRateProvider FIXED = new FixedCdiRateProvider(CDI);
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 23);

    private static CdiSchedule until(LocalDate date) {
        return FIXED.schedule(MONDAY.minusYears(3), date);
    }

    private SavingsBox box(String goal) {
        return SavingsBox.create(UUID.randomUUID(), UUID.randomUUID(), "  Viagem  ", goal == null ? null : Money.of(goal), MONDAY);
    }

    /** Caixinha com lotes já rendidos: (dias atrás de hoje, principal, saldo). */
    private static SavingsBox withLots(LocalDate today, Object[]... lots) {
        List<SavingsLot> list = java.util.Arrays.stream(lots).map(l -> SavingsLot.reconstitute(UUID.randomUUID(),
                today.minusDays((Integer) l[0]), new BigDecimal((String) l[1]), new BigDecimal((String) l[2]),
                BigDecimal.ZERO)).toList();
        return SavingsBox.reconstitute(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Lotes", null, list,
                Money.ZERO, SavingsBox.Status.ACTIVE, today.minusDays(1), null, LocalDateTime.now(), LocalDateTime.now(), null, 0L);
    }

    @Test
    void dailyRateIsTheCompoundEquivalentOver252BusinessDays() {
        BigDecimal daily = YieldPolicy.dailyRate(CDI);
        assertThat(Math.pow(1 + daily.doubleValue(), 252)).isCloseTo(1.1415, Offset.offset(1e-9));
    }

    @Test
    void createsActiveEmptyBoxWithTrimmedName() {
        SavingsBox box = box(null);
        assertThat(box.getName()).isEqualTo("Viagem");
        assertThat(box.getBalance()).isEqualTo(Money.ZERO);
        assertThat(box.getStatus()).isEqualTo(SavingsBox.Status.ACTIVE);
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
    void eachDepositIsALot() {
        SavingsBox box = box(null);
        box.deposit(Money.of("100.00"), MONDAY);
        box.deposit(Money.of("50.00"), MONDAY.plusDays(1));

        assertThat(box.getLots()).hasSize(2);
        assertThat(box.getBalance()).isEqualTo(Money.of("150.00"));
    }

    @Test
    void oneBusinessDayCreditsWholeCentsAndKeepsTheFraction() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"), MONDAY);

        Money credited = box.accrueYield(until(MONDAY.plusDays(1)));

        // 1000 * 0,000525... = 0,525... -> 0,52 no saldo, o resto fica pendente no lote
        assertThat(credited).isEqualTo(Money.of("0.52"));
        assertThat(box.getBalance()).isEqualTo(Money.of("1000.52"));
        assertThat(box.getLots().get(0).getPendingYield()).isBetween(new BigDecimal("0.005"), new BigDecimal("0.006"));
    }

    @Test
    void depositDoesNotYieldOnTheDayItWasMade_evenIfTheJobIsLate() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"), MONDAY);
        box.deposit(Money.of("1000.00"), MONDAY.plusDays(1)); // terça, antes do job render a terça

        box.accrueYield(until(MONDAY.plusDays(1)));

        assertThat(box.getLots().get(0).getBalance()).isEqualByComparingTo("1000.52");
        assertThat(box.getLots().get(1).getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void weekendsAndHolidaysDoNotYield() {
        LocalDate thursday = LocalDate.of(2026, 11, 19);
        SavingsBox box = SavingsBox.create(UUID.randomUUID(), UUID.randomUUID(), "F", null, thursday);
        box.deposit(Money.of("1000.00"), thursday);

        // sexta 20/11 é feriado; sábado e domingo; só a segunda 23 rende
        Money credited = box.accrueYield(FIXED.schedule(thursday, thursday.plusDays(4)));

        assertThat(credited).isEqualTo(Money.of("0.52"));
        assertThat(box.getLastYieldDate()).isEqualTo(thursday.plusDays(4));
    }

    @Test
    void accrualIsIdempotentForTheSamePeriod() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"), MONDAY);
        box.accrueYield(until(MONDAY.plusDays(1)));

        assertThat(box.accrueYield(until(MONDAY.plusDays(1)))).isEqualTo(Money.ZERO);
        assertThat(box.accrueYield(until(MONDAY))).isEqualTo(Money.ZERO);
        assertThat(box.getBalance()).isEqualTo(Money.of("1000.52"));
    }

    @Test
    void unpublishedDaysDoNotAdvance() {
        SavingsBox box = box(null);
        box.deposit(Money.of("1000.00"), MONDAY);

        // taxa só até terça publicada, embora tenham pedido até sexta
        box.accrueYield(new CdiSchedule(until(MONDAY.plusDays(4)).dailyRates(), MONDAY.plusDays(1), "teste"));

        assertThat(box.getLastYieldDate()).isEqualTo(MONDAY.plusDays(1));
        assertThat(box.getBalance()).isEqualTo(Money.of("1000.52"));
    }

    @Test
    void smallBalanceStillYieldsBecauseFractionsAccumulate() {
        SavingsBox box = box(null);
        box.deposit(Money.of("10.00"), MONDAY); // rende ~R$ 0,005 por dia: arredondar todo dia daria zero

        box.accrueYield(until(MONDAY.plusDays(14))); // 10 dias úteis

        assertThat(box.getTotalYield()).isEqualTo(Money.of("0.05"));
    }

    @Test
    void oneYearOfBusinessDaysMatchesTheAnnualCdi() {
        SavingsBox box = box(null);
        box.deposit(Money.of("10000.00"), MONDAY);

        LocalDate end = MONDAY;
        while (BusinessCalendar.businessDaysBetween(MONDAY, end) < 252) end = end.plusDays(1);
        box.accrueYield(until(end));

        // 14,15% a.a. sobre 10.000 = 1.415,00 (com capitalização diária, sem perder frações)
        assertThat(box.getBalance().amount()).isBetween(new BigDecimal("11414.99"), new BigDecimal("11415.01"));
    }

    @Test
    void withdrawalTakesIofAndIncomeTaxFromTheGain() {
        LocalDate today = MONDAY;
        SavingsBox box = withLots(today, new Object[]{10, "1000.00", "1010.00"});

        SavingsBox.Withdrawal w = box.withdraw(Money.of("1010.00"), today);

        // ganho 10; IOF (10 dias) 66% = 6,60; IR 22,5% de 3,40 = 0,76
        assertThat(w.gain()).isEqualTo(Money.of("10.00"));
        assertThat(w.iof()).isEqualTo(Money.of("6.60"));
        assertThat(w.incomeTax()).isEqualTo(Money.of("0.76"));
        assertThat(w.net()).isEqualTo(Money.of("1002.64"));
        assertThat(box.getLots()).isEmpty();
    }

    @Test
    void withdrawalConsumesOldestLotsFirst() {
        LocalDate today = MONDAY;
        SavingsBox box = withLots(today,
                new Object[]{400, "100.00", "150.00"},  // antigo: IR 17,5%, sem IOF
                new Object[]{5, "100.00", "101.00"});   // novo: IOF 83%, IR 22,5%

        SavingsBox.Withdrawal first = box.withdraw(Money.of("150.00"), today);
        assertThat(first.incomeTax()).isEqualTo(Money.of("8.75")); // 17,5% de 50
        assertThat(first.iof()).isEqualTo(Money.ZERO);
        assertThat(first.net()).isEqualTo(Money.of("141.25"));
        assertThat(box.getLots()).hasSize(1);

        // 50 do lote novo: principal proporcional 49,50, ganho 0,50; IOF 0,41; IR 22,5% de 0,09 = 0,02
        SavingsBox.Withdrawal second = box.withdraw(Money.of("50.00"), today);
        assertThat(second.gain()).isEqualTo(Money.of("0.50"));
        assertThat(second.iof()).isEqualTo(Money.of("0.41"));
        assertThat(second.incomeTax()).isEqualTo(Money.of("0.02"));
        assertThat(second.net()).isEqualTo(Money.of("49.57"));
        assertThat(box.getLots().get(0).getPrincipal()).isEqualByComparingTo("50.50");
        assertThat(box.getBalance()).isEqualTo(Money.of("51.00"));
    }

    @Test
    void previewDoesNotChangeTheBox() {
        SavingsBox box = withLots(MONDAY, new Object[]{400, "100.00", "150.00"});

        SavingsBox.Withdrawal preview = box.previewFullWithdrawal(MONDAY);

        assertThat(preview.net()).isEqualTo(Money.of("141.25"));
        assertThat(box.getBalance()).isEqualTo(Money.of("150.00"));
    }

    @Test
    void withdrawingWhatWasJustDepositedHasNoTax() {
        SavingsBox box = box(null);
        box.deposit(Money.of("300.00"), MONDAY);

        SavingsBox.Withdrawal w = box.withdraw(Money.of("100.00"), MONDAY);

        assertThat(w.net()).isEqualTo(Money.of("100.00"));
        assertThat(box.getBalance()).isEqualTo(Money.of("200.00"));
    }

    @Test
    void goalIsReachedOnceByDepositOrByYield() {
        SavingsBox box = box("100.00");
        assertThat(box.deposit(Money.of("99.80"), MONDAY)).isFalse();

        box.accrueYield(until(MONDAY.plusDays(14))); // 99,80 rendendo 10 dias úteis passa de 100
        assertThat(box.getGoalReachedAt()).isNotNull();

        assertThat(box.deposit(Money.of("50.00"), MONDAY.plusDays(14))).isFalse(); // já tinha atingido
    }

    @Test
    void withdrawCannotExceedBalance() {
        SavingsBox box = box(null);
        box.deposit(Money.of("50.00"), MONDAY);

        assertThatThrownBy(() -> box.withdraw(Money.of("50.01"), MONDAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void closeRequiresEmptyBoxAndThenBlocksEverything() {
        SavingsBox box = box(null);
        box.deposit(Money.of("80.00"), MONDAY);
        assertThatThrownBy(box::close).isInstanceOf(BusinessRuleException.class);

        box.withdraw(Money.of("80.00"), MONDAY);
        box.close();

        assertThat(box.getStatus()).isEqualTo(SavingsBox.Status.CLOSED);
        assertThatThrownBy(() -> box.deposit(Money.of("1.00"), MONDAY)).isInstanceOf(BusinessRuleException.class);
        assertThat(box.accrueYield(until(MONDAY.plusDays(10)))).isEqualTo(Money.ZERO);
    }

    @Test
    void zeroAmountIsRejected() {
        SavingsBox box = box(null);
        assertThatThrownBy(() -> box.deposit(Money.ZERO, MONDAY)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> box.withdraw(Money.ZERO, MONDAY)).isInstanceOf(ValidationException.class);
    }
}
