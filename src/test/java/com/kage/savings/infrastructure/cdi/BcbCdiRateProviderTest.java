package com.kage.savings.infrastructure.cdi;

import com.kage.savings.domain.service.CdiRateProvider;
import com.kage.savings.domain.valueobject.CdiSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Respostas no formato real da API do SGS (capturadas de api.bcb.gov.br em 25/11/2026). */
class BcbCdiRateProviderTest {

    private MockRestServiceServer server;
    private BcbCdiRateProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.bcb.gov.br");
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new BcbCdiRateProvider(builder.build());
    }

    private void respond(String from, String to, String json) {
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial="
                        + from + "&dataFinal=" + to))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void holidayInsideTheSeriesHasNoRate_andRatesAreConvertedFromPercent() {
        respond("19/11/2026", "24/11/2026", """
                [{"data":"19/11/2026","valor":"0.050788"},{"data":"23/11/2026","valor":"0.050788"},
                 {"data":"24/11/2026","valor":"0.050788"}]""");

        CdiSchedule s = provider.schedule(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 24));

        assertThat(s.rateOn(LocalDate.of(2026, 11, 19))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("0.00050788"));
        assertThat(s.rateOn(LocalDate.of(2026, 11, 20))).isEmpty(); // feriado: a série não tem
        assertThat(s.confirmedUntil()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(s.source()).isEqualTo("BCB SGS 12");
    }

    @Test
    void stopsAtTheLastPublishedBusinessDay() {
        // pediu até quarta; a taxa de quarta ainda não saiu
        respond("23/11/2026", "25/11/2026", """
                [{"data":"23/11/2026","valor":"0.050788"},{"data":"24/11/2026","valor":"0.050788"}]""");

        CdiSchedule s = provider.schedule(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 25));

        assertThat(s.confirmedUntil()).isEqualTo(LocalDate.of(2026, 11, 24));
    }

    @Test
    void weekendAfterTheLastRateIsConfirmedWithoutWaiting() {
        respond("27/11/2026", "29/11/2026", """
                [{"data":"27/11/2026","valor":"0.050788"}]""");

        CdiSchedule s = provider.schedule(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 29));

        assertThat(s.confirmedUntil()).isEqualTo(LocalDate.of(2026, 11, 29));
    }

    @Test
    void notFoundMeansNothingPublished() {
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial=28/11/2026&dataFinal=29/11/2026"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"erro\":{\"statusCode\":404,\"detail\":\"Value(s) not found\"}}"));

        CdiSchedule s = provider.schedule(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 29));

        assertThat(s.dailyRates()).isEmpty();
        assertThat(s.confirmedUntil()).isEqualTo(LocalDate.of(2026, 11, 29)); // fim de semana: nada a esperar
    }

    @Test
    void serverErrorIsUnavailable_neverAGuess() {
        server.expect(requestTo("https://api.bcb.gov.br/dados/serie/bcdata.sgs.12/dados?formato=json&dataInicial=24/11/2026&dataFinal=24/11/2026"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.schedule(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24)))
                .isInstanceOf(CdiRateProvider.CdiUnavailableException.class);
    }
}
