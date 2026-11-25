package com.kage.payment.domain.service;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Janela noturna do PIX (Resolução BCB nº 142): das 20h às 6h vale o limite noturno, que costuma ser
 * bem menor que o diurno — é a principal defesa contra golpe/sequestro relâmpago.
 */
public final class PixNightWindow {

    public static final LocalTime START = LocalTime.of(20, 0);
    public static final LocalTime END = LocalTime.of(6, 0);

    private PixNightWindow() {
    }

    public static boolean isNight(LocalDateTime moment) {
        LocalTime time = moment.toLocalTime();
        return !time.isBefore(START) || time.isBefore(END);
    }

    /** Início da janela noturna em que {@code moment} está (20h de hoje, ou de ontem se for madrugada). */
    public static LocalDateTime startOfCurrentNight(LocalDateTime moment) {
        if (!isNight(moment)) {
            throw new IllegalArgumentException("Fora da janela noturna: " + moment);
        }
        LocalDateTime todayStart = moment.toLocalDate().atTime(START);
        return moment.toLocalTime().isBefore(END) ? todayStart.minusDays(1) : todayStart;
    }
}
