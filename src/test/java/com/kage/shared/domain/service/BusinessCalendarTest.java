package com.kage.shared.domain.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {

    @Test
    void easterMatchesKnownDates() {
        assertThat(BusinessCalendar.easter(2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(BusinessCalendar.easter(2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(BusinessCalendar.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(BusinessCalendar.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    }

    @Test
    void movableHolidays2026() {
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 2, 16))).isFalse(); // segunda de Carnaval
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 2, 17))).isFalse(); // terça de Carnaval
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 2, 18))).isTrue();  // quarta de cinzas: abre
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 4, 3))).isFalse();  // Sexta-feira Santa
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 6, 4))).isFalse();  // Corpus Christi
    }

    @Test
    void fixedHolidaysAndWeekends() {
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 11, 20))).isFalse(); // Consciência Negra (sexta)
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 11, 21))).isFalse(); // sábado
        assertThat(BusinessCalendar.isBusinessDay(LocalDate.of(2026, 11, 23))).isTrue();
    }

    @Test
    void countsBusinessDaysExcludingStartIncludingEnd() {
        // 16/11 (seg) até 23/11 (seg): 17, 18, 19 e 23 — o 20 é feriado
        assertThat(BusinessCalendar.businessDaysBetween(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23))).isEqualTo(4);
    }
}
