package com.kage.payment.domain.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Janela noturna do PIX (Resolução BCB nº 142): das 20h às 6h vale o limite noturno, que costuma ser
 * bem menor que o diurno — é a principal defesa contra golpe/sequestro relâmpago. O cliente pode
 * começar a janela mais tarde, até as 22h (o fim é sempre às 6h).
 */
public final class PixNightWindow {

    public static final int DEFAULT_START_HOUR = 20;
    public static final int LATEST_START_HOUR = 22;
    public static final LocalTime START = LocalTime.of(DEFAULT_START_HOUR, 0);
    public static final LocalTime END = LocalTime.of(6, 0);

    private PixNightWindow() {
    }

    public static boolean isNight(LocalDateTime moment) {
        return isNight(moment, DEFAULT_START_HOUR);
    }

    public static boolean isNight(LocalDateTime moment, int startHour) {
        LocalTime time = moment.toLocalTime();
        return !time.isBefore(LocalTime.of(startHour, 0)) || time.isBefore(END);
    }

    /** Início da janela noturna em que {@code moment} está (20h de hoje, ou de ontem se for madrugada). */
    public static LocalDateTime startOfCurrentNight(LocalDateTime moment) {
        return startOfCurrentNight(moment, DEFAULT_START_HOUR);
    }

    public static LocalDateTime startOfCurrentNight(LocalDateTime moment, int startHour) {
        if (!isNight(moment, startHour)) {
            throw new IllegalArgumentException("Fora da janela noturna: " + moment);
        }
        LocalDateTime todayStart = moment.toLocalDate().atTime(startHour, 0);
        return moment.toLocalTime().isBefore(END) ? todayStart.minusDays(1) : todayStart;
    }
}
