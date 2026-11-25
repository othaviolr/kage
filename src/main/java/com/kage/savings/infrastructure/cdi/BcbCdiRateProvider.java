package com.kage.savings.infrastructure.cdi;

import com.kage.savings.domain.service.BusinessCalendar;
import com.kage.savings.domain.service.CdiRateProvider;
import com.kage.savings.domain.valueobject.CdiSchedule;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * CDI de verdade: série 12 do SGS do Banco Central (taxa DI diária, em % ao dia, só dias úteis).
 *
 * A série é o calendário: dia útil sem taxa ANTES da última publicada foi feriado; DEPOIS dela, ainda
 * não foi publicada — e o {@code confirmedUntil} para ali, para não render um dia sem saber a taxa.
 * Fins de semana e feriados nacionais conhecidos depois da última taxa não precisam esperar.
 */
public class BcbCdiRateProvider implements CdiRateProvider {

    static final String SOURCE = "BCB SGS 12";
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    record Point(String data, String valor) {}

    private final RestClient restClient;

    /** @param restClient já com a base URL (https://api.bcb.gov.br) e timeouts */
    public BcbCdiRateProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public CdiSchedule schedule(LocalDate fromExclusive, LocalDate toInclusive) {
        Map<LocalDate, BigDecimal> rates = new HashMap<>();
        for (Point p : fetch(fromExclusive.plusDays(1), toInclusive)) {
            LocalDate date = LocalDate.parse(p.data(), BR);
            rates.put(date, new BigDecimal(p.valor()).divide(PERCENT)); // % a.d. -> fração
        }

        LocalDate confirmed = rates.keySet().stream().max(LocalDate::compareTo).orElse(fromExclusive);
        // depois da última taxa publicada, só avança por dias que não teriam taxa de qualquer jeito
        while (confirmed.isBefore(toInclusive) && !BusinessCalendar.isBusinessDay(confirmed.plusDays(1))) {
            confirmed = confirmed.plusDays(1);
        }
        return new CdiSchedule(rates, confirmed, SOURCE);
    }

    private Point[] fetch(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) return new Point[0];
        try {
            Point[] points = restClient.get()
                    // datas literais (dd/MM/yyyy), como a documentação do SGS: variável de URI viraria %2F
                    .uri("/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial=" + from.format(BR)
                            + "&dataFinal=" + to.format(BR))
                    .retrieve()
                    .body(Point[].class);
            return points == null ? new Point[0] : points;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) return new Point[0]; // "Value(s) not found": nada no período
            throw new CdiUnavailableException("Banco Central respondeu " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new CdiUnavailableException("Banco Central indisponível: " + e.getMessage(), e);
        }
    }
}
