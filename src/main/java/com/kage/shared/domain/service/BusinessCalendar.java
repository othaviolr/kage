package com.kage.shared.domain.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dias úteis bancários nacionais (o calendário da ANBIMA/B3 que o CDI segue): segunda a sexta,
 * menos os feriados nacionais — os de data fixa e os móveis, que dependem da Páscoa (Carnaval,
 * Sexta-feira Santa, Corpus Christi).
 *
 * Com a série do CDI do Banco Central, o calendário real é a própria série (feriado não tem taxa
 * publicada); este serve para a fonte fixa e para saber se um dia sem taxa é "ainda não publicado"
 * ou "não haverá taxa".
 */
public final class BusinessCalendar {

    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(1, 1),   // Confraternização Universal
            MonthDay.of(4, 21),  // Tiradentes
            MonthDay.of(5, 1),   // Dia do Trabalho
            MonthDay.of(9, 7),   // Independência
            MonthDay.of(10, 12), // Nossa Senhora Aparecida
            MonthDay.of(11, 2),  // Finados
            MonthDay.of(11, 15), // Proclamação da República
            MonthDay.of(11, 20), // Consciência Negra (nacional desde a Lei 14.759/2023)
            MonthDay.of(12, 25)  // Natal
    );

    private static final Map<Integer, Set<LocalDate>> MOVABLE = new ConcurrentHashMap<>();

    private BusinessCalendar() {
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        if (FIXED_HOLIDAYS.contains(MonthDay.from(date))) return false;
        return !MOVABLE.computeIfAbsent(date.getYear(), BusinessCalendar::movableHolidays).contains(date);
    }

    /** Dias úteis em (fromExclusive, toInclusive]. */
    public static int businessDaysBetween(LocalDate fromExclusive, LocalDate toInclusive) {
        int days = 0;
        for (LocalDate d = fromExclusive.plusDays(1); !d.isAfter(toInclusive); d = d.plusDays(1)) {
            if (isBusinessDay(d)) days++;
        }
        return days;
    }

    private static Set<LocalDate> movableHolidays(int year) {
        LocalDate easter = easter(year);
        Set<LocalDate> days = new HashSet<>();
        days.add(easter.minusDays(48)); // segunda de Carnaval
        days.add(easter.minusDays(47)); // terça de Carnaval
        days.add(easter.minusDays(2));  // Sexta-feira Santa
        days.add(easter.plusDays(60));  // Corpus Christi
        return days;
    }

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher, calendário gregoriano). */
    static LocalDate easter(int year) {
        int a = year % 19, b = year / 100, c = year % 100, d = b / 4, e = b % 4;
        int f = (b + 8) / 25, g = (b - f + 1) / 3, h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
