package com.kage.card.infrastructure.export;

import com.kage.card.application.usecase.InvoiceDocuments;
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

import static org.assertj.core.api.Assertions.assertThat;

class PdfInvoiceRendererTest {

    private final PdfInvoiceRenderer renderer = new PdfInvoiceRenderer();

    private static InvoiceDocuments.Statement statement(List<InvoiceDocuments.Line> lines) {
        return new InvoiceDocuments.Statement("João Conceição", "4821", "2026-09", LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 20), "CLOSED", new BigDecimal("1100.00"), new BigDecimal("165.00"),
                BigDecimal.ZERO, new BigDecimal("1100.00"), new BigDecimal("5000.00"), new BigDecimal("3900.00"), lines,
                List.of(new InvoiceDocuments.Rate("Juros do rotativo", new BigDecimal("0.14"), new BigDecimal("3.8179")),
                        new InvoiceDocuments.Rate("Multa por atraso (uma vez)", new BigDecimal("0.02"), null)),
                "AB12-CD34-EF56", LocalDateTime.of(2026, 9, 11, 8, 30));
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
    void fatura_trazResumoLancamentosETaxas() throws IOException {
        byte[] pdf = renderer.render(statement(List.of(
                new InvoiceDocuments.Line(LocalDate.of(2026, 9, 2), "Padaria São José", new BigDecimal("1500.00"), false, null),
                new InvoiceDocuments.Line(LocalDate.of(2026, 9, 3), "Assinatura", new BigDecimal("39.90"), false, "Streaming"),
                new InvoiceDocuments.Line(LocalDate.of(2026, 9, 5), "Crédito de contestação: Loja", new BigDecimal("439.90"), true, null))));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text)
                .contains("Fatura de 09/2026")
                .contains("João Conceição")
                .contains("cartão final 4821")
                .contains("R$ 1.100,00")
                .contains("R$ 165,00")
                .contains("20/09/2026")
                .contains("Fechada")
                .contains("Limite disponível")
                .contains("Padaria São José")
                .contains("Assinatura (virtual Streaming)")
                .contains("- R$ 439,90")
                .contains("14,00% ao mês")
                .contains("381,79% ao ano")
                .contains("Multa por atraso (uma vez)")
                .contains("Lei 14.690/2023")
                .contains("Autenticação: AB12-CD34-EF56");
    }

    @Test
    void muitosLancamentos_quebraPagina() throws IOException {
        List<InvoiceDocuments.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            lines.add(new InvoiceDocuments.Line(LocalDate.of(2026, 9, 1), "Compra " + i, new BigDecimal("10.00"), false, null));
        }

        byte[] pdf = renderer.render(statement(lines));

        assertThat(pages(pdf)).isGreaterThan(1);
        assertThat(text(pdf)).contains("Compra 1 ").contains("Compra 120").contains("continuação da fatura (página 2)");
    }

    @Test
    void descricaoLongaEhCortada_eCaractereForaDaFonteNaoDerruba() throws IOException {
        byte[] pdf = renderer.render(statement(List.of(new InvoiceDocuments.Line(LocalDate.of(2026, 9, 2),
                "Loja com um nome enorme que não cabe na linha da fatura de jeito nenhum mesmo 🍕 ≠",
                new BigDecimal("10.00"), false, null))));

        assertThat(text(pdf)).contains("Loja com um nome enorme").contains("...");
    }
}
