package com.kage.payment.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PixNightWindowTest {

    @ParameterizedTest
    @CsvSource({
            "2026-11-25T19:59:59, false",
            "2026-11-25T20:00:00, true",
            "2026-11-25T23:59:59, true",
            "2026-11-26T00:00:00, true",
            "2026-11-26T05:59:59, true",
            "2026-11-26T06:00:00, false",
            "2026-11-26T12:00:00, false"
    })
    void isNight_deveRespeitarAJanelaDas20hAs6h(LocalDateTime moment, boolean expected) {
        assertThat(PixNightWindow.isNight(moment)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-25T21:15:00, 2026-11-25T20:00:00",
            "2026-11-26T03:40:00, 2026-11-25T20:00:00",
            "2026-12-01T00:10:00, 2026-11-30T20:00:00"
    })
    void startOfCurrentNight_deveVoltarParaAs20hCorretas(LocalDateTime moment, LocalDateTime expectedStart) {
        assertThat(PixNightWindow.startOfCurrentNight(moment)).isEqualTo(expectedStart);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-25T21:59:59, false",
            "2026-11-25T22:00:00, true",
            "2026-11-26T05:59:59, true",
            "2026-11-26T06:00:00, false"
    })
    void janelaQueComecaAs22h(LocalDateTime moment, boolean expected) {
        assertThat(PixNightWindow.isNight(moment, 22)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-25T23:15:00, 21, 2026-11-25T21:00:00",
            "2026-11-26T03:40:00, 22, 2026-11-25T22:00:00"
    })
    void inicioDaNoite_comOHorarioDoCliente(LocalDateTime moment, int startHour, LocalDateTime expectedStart) {
        assertThat(PixNightWindow.startOfCurrentNight(moment, startHour)).isEqualTo(expectedStart);
    }
}
