package com.kage.savings.infrastructure.report;

import com.kage.savings.application.usecase.GetIncomeReport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfIncomeReportRendererTest {

    private final PdfIncomeReportRenderer renderer = new PdfIncomeReportRenderer();

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static GetIncomeReport.DebtLine debt(String description, boolean mustDeclare) {
        return new GetIncomeReport.DebtLine(UUID.randomUUID(), "ACTIVE", LocalDate.of(2025, 3, 1), description,
                bd("0.00"), bd("8450.32"), bd("3150.48"), mustDeclare);
    }

    private static GetIncomeReport.Output report(boolean partial, List<GetIncomeReport.DebtLine> debts) {
        return new GetIncomeReport.Output(2025, partial, "João Conceição", "123.456.789-09",
                LocalDate.of(2024, 12, 31), partial ? LocalDate.of(2025, 11, 25) : LocalDate.of(2025, 12, 31),
                List.of(new GetIncomeReport.AccountLine("0001", "10000001-5", "CHECKING", bd("100.00"), bd("1250.75"))),
                List.of(new GetIncomeReport.SavingsLine(UUID.randomUUID(), "Reserva", "ACTIVE", bd("0.00"), bd("2000.00"),
                                bd("40.00"), bd("0.00"), bd("8.00"), bd("32.00")),
                        new GetIncomeReport.SavingsLine(UUID.randomUUID(), "Viagem", "CLOSED", bd("300.00"), bd("0.00"),
                                bd("0.00"), bd("0.00"), bd("0.00"), bd("0.00"))),
                debts,
                new GetIncomeReport.Totals(bd("40.00"), bd("0.00"), bd("8.00"), bd("32.00"),
                        debts.isEmpty() ? bd("0.00") : bd("8450.32"), debts.isEmpty() ? bd("0.00") : bd("3150.48")),
                new GetIncomeReport.DeclarationGuide("Bens e Direitos: conta corrente no grupo 06.",
                        "Rendimentos Sujeitos à Tributação Exclusiva/Definitiva, item 06: R$ 32.00.",
                        "Dívidas e Ônus Reais, código 11. 1 empréstimo precisa ser declarado."),
                LocalDateTime.of(2026, 3, 2, 9, 15));
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    void informe_trazAsTresSecoesDaDeclaracao_eOComoDeclarar() throws IOException {
        byte[] pdf = renderer.render(report(false, List.of(debt(
                "Empréstimo pessoal contratado no Kage em 01/03/2025: R$ 12.180,00 financiados (IOF incluso) em 24 parcelas de R$ 787,51.",
                true))), "AB12-CD34-EF56");

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text)
                .contains("Informe de rendimentos 2025")
                .contains("Ano-calendário 2025")
                .contains("declaração de 2026")
                .doesNotContain("PARCIAL")
                .contains("João Conceição")
                .contains("123.456.789-09")
                // Bens e Direitos
                .contains("Saldo em 31/12/2024")
                .contains("Saldo em 31/12/2025")
                .contains("Conta corrente — ag. 0001, conta 10000001-5")
                .contains("06 / 01")
                .contains("R$ 1.250,75")
                .contains("Caixinha \"Reserva\"")
                .contains("Caixinha \"Viagem\" (encerrada)")
                .contains("04 / 02")
                // tributação exclusiva: só a que teve resgate
                .contains("Total (item 06")
                .contains("R$ 40,00")
                .contains("R$ 32,00")
                // dívidas
                .contains("Empréstimo pessoal contratado no Kage em 01/03/2025")
                .contains("R$ 787,51") // a quebra de linha não separa o R$ do valor
                .contains("Código 11")
                .contains("R$ 8.450,32")
                .contains("Pago no ano: R$ 3.150,48")
                .contains("Declarar")
                // como declarar e autenticação
                .contains("Como declarar")
                .contains("1 empréstimo precisa ser declarado.")
                .contains("Autenticação: AB12-CD34-EF56")
                .contains("Gerado em 02/03/2026 09:15");
    }

    @Test
    void anoCorrente_saiMarcadoComoParcial() throws IOException {
        String text = text(renderer.render(report(true, List.of()), "X"));

        assertThat(text).contains("PARCIAL: valores até 25/11/2025").contains("não use este documento na declaração")
                .contains("Saldo em 25/11/2025")
                .contains("Nenhum empréstimo no período.");
    }

    @Test
    void muitasDividas_quebraPagina_comCabecalhoDeContinuacao() throws IOException {
        List<GetIncomeReport.DebtLine> debts = new ArrayList<>();
        for (int i = 0; i < 30; i++) debts.add(debt("Empréstimo número " + i, i % 2 == 0));
        byte[] pdf = renderer.render(report(false, debts), "X");

        assertThat(pages(pdf)).isGreaterThan(1);
        String text = text(pdf);
        assertThat(text).contains("continuação do informe de rendimentos 2025 (página 2)")
                .contains("Empréstimo número 29")
                .contains("Dispensado de declarar (até R$ 5.000,00)");
    }
}
