package com.kage.card.infrastructure.fx;

import com.kage.card.domain.service.ExchangeRateProvider;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * PTAX de venda do Banco Central (API Olinda, CotacaoMoedaDia). O BC publica vários boletins por dia;
 * vale o último (o de fechamento, quando já saiu). Fim de semana, feriado ou dia ainda sem boletim: a
 * cotação do dia útil anterior (até {@value #MAX_DAYS_BACK} dias para trás).
 */
public class BcbPtaxExchangeRateProvider implements ExchangeRateProvider {

    static final String SOURCE = "PTAX (Banco Central)";
    static final int MAX_DAYS_BACK = 7;
    private static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM-dd-yyyy");

    record Quote(BigDecimal cotacaoVenda, String dataHoraCotacao, String tipoBoletim) {}

    record Response(Quote[] value) {}

    private final RestClient restClient;

    /** @param restClient já com a base URL (https://olinda.bcb.gov.br) e timeouts */
    public BcbPtaxExchangeRateProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Rate sellingRate(String currency, LocalDate date) {
        for (int back = 0; back <= MAX_DAYS_BACK; back++) {
            LocalDate day = date.minusDays(back);
            Quote[] quotes = fetch(currency, day);
            if (quotes.length > 0) {
                return new Rate(currency, quotes[quotes.length - 1].cotacaoVenda(), day, SOURCE);
            }
        }
        throw new ExchangeRateUnavailableException("Cotação de " + currency + " indisponível no Banco Central");
    }

    private Quote[] fetch(String currency, LocalDate day) {
        try {
            Response response = restClient.get()
                    .uri("/olinda/servico/PTAX/versao/v1/odata/CotacaoMoedaDia(moeda=@moeda,dataCotacao=@dataCotacao)"
                            + "?@moeda='" + currency + "'&@dataCotacao='" + day.format(US) + "'&$format=json")
                    .retrieve()
                    .body(Response.class);
            return response == null || response.value() == null ? new Quote[0] : response.value();
        } catch (RestClientException e) {
            throw new ExchangeRateUnavailableException("Cotação do câmbio indisponível agora: tente de novo em instantes");
        }
    }
}
