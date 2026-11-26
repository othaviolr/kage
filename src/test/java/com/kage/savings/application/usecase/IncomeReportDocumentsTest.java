package com.kage.savings.application.usecase;

import com.kage.savings.application.query.IncomeReportSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IncomeReportDocumentsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private static final UUID CUSTOMER = UUID.randomUUID();

    /** Uma conta com o saldo dado; nada mais. */
    private static IncomeReportSource source(String balance) {
        return new IncomeReportSource() {
            public Optional<Holder> holder(UUID customerId) { return Optional.of(new Holder("Ana", "12345678909")); }
            public List<AccountBalances> accounts(UUID c, LocalDateTime from, LocalDateTime to) {
                return List.of(new AccountBalances(UUID.randomUUID(), "0001", "1-0", "CHECKING", BigDecimal.ZERO, new BigDecimal(balance)));
            }
            public List<BoxFigures> savingsBoxes(UUID c, LocalDateTime from, LocalDateTime to) { return List.of(); }
            public List<LoanFigures> loans(UUID c, LocalDateTime from, LocalDateTime to) { return List.of(); }
        };
    }

    @Test
    void nomeDoArquivo_eCodigoQueMudaComOsNumeros() {
        AtomicReference<String> code = new AtomicReference<>();
        IncomeReportDocuments.Renderer renderer = (report, authenticationCode) -> {
            code.set(authenticationCode);
            return new byte[]{1};
        };

        var closed = new IncomeReportDocuments(new GetIncomeReport(source("100.00"), CLOCK), renderer).pdf(CUSTOMER, 2025);
        assertThat(closed.fileName()).isEqualTo("informe-de-rendimentos-kage-2025.pdf");
        String original = code.get();
        assertThat(original).matches("[0-9A-F]{4}(-[0-9A-F]{4}){7}");

        // o mesmo informe gera o mesmo código (o banco consegue conferir); outro saldo, outro código
        new IncomeReportDocuments(new GetIncomeReport(source("100.0"), CLOCK), renderer).pdf(CUSTOMER, 2025);
        assertThat(code.get()).isEqualTo(original);
        new IncomeReportDocuments(new GetIncomeReport(source("100.01"), CLOCK), renderer).pdf(CUSTOMER, 2025);
        assertThat(code.get()).isNotEqualTo(original);

        var partial = new IncomeReportDocuments(new GetIncomeReport(source("100.00"), CLOCK), renderer).pdf(CUSTOMER, 2026);
        assertThat(partial.fileName()).isEqualTo("informe-de-rendimentos-kage-2026-parcial.pdf");
    }
}
