package com.kage.card.infrastructure.export;

import com.kage.card.application.usecase.InvoiceDocuments;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.Map;

/**
 * Fatura em PDF com o PDFBox: fontes padrão (Helvetica, sem embutir arquivo de fonte), A4, quebra de
 * página nos lançamentos. Caractere que a codificação da fonte não tem vira "?" em vez de derrubar a
 * geração.
 */
public class PdfInvoiceRenderer implements InvoiceDocuments.Renderer {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");
    private static final Map<String, String> STATUS = Map.of(
            "CLOSED", "Fechada",
            "PAID", "Paga",
            "ROLLED_OVER", "Saldo levado para a fatura seguinte (rotativo)",
            "FINANCED", "Parcelada (as parcelas estão nas faturas seguintes)");

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth();
    private static final float HEIGHT = PDRectangle.A4.getHeight();
    private static final float RIGHT = WIDTH - MARGIN;
    private static final float LINE = 15;

    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @Override
    public byte[] render(InvoiceDocuments.Statement s) {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Fatura Kage " + month(s.referenceMonth()));
            info.setAuthor("Kage");
            info.setCreationDate(GregorianCalendar.from(s.generatedAt().atZone(java.time.ZoneId.systemDefault())));

            Writer w = new Writer(document);
            w.text(bold, 20, MARGIN, "Kage");
            w.textRight(bold, 12, RIGHT, "Fatura de " + month(s.referenceMonth()));
            w.down(22);
            w.text(regular, 11, MARGIN, "Fatura do cartão de crédito");
            w.down(LINE);
            w.text(regular, 10, MARGIN, s.holderName()
                    + (s.cardLastDigits() == null ? "" : "  •  cartão final " + s.cardLastDigits()));
            w.down(28);

            // resumo: o que o cliente precisa ver primeiro
            w.text(bold, 10, MARGIN, "Total da fatura");
            w.text(bold, 10, 230, "Pagamento mínimo");
            w.text(bold, 10, 400, "Vencimento");
            w.down(18);
            w.text(bold, 16, MARGIN, money(s.total()));
            w.text(regular, 14, 230, money(s.minimumPayment()));
            w.text(regular, 14, 400, s.dueDate().format(DATE));
            w.down(24);
            w.label("Fechamento", s.closingDate().format(DATE));
            w.label("Situação", STATUS.getOrDefault(s.status(), s.status()));
            w.label("Já pago", money(s.paidAmount()));
            w.label("Saldo devedor", money(s.remaining()));
            w.label("Limite total", money(s.creditLimit()));
            w.label("Limite disponível", money(s.availableLimit()));
            w.down(14);

            w.section("Lançamentos");
            w.text(bold, 9, MARGIN, "Data");
            w.text(bold, 9, 115, "Descrição");
            w.textRight(bold, 9, RIGHT, "Valor");
            w.down(LINE);
            if (s.lines().isEmpty()) {
                w.text(regular, 9, MARGIN, "Nenhum lançamento.");
                w.down(LINE);
            }
            for (InvoiceDocuments.Line line : s.lines()) {
                w.ensureSpace(LINE);
                String description = line.virtualCard() == null
                        ? line.description()
                        : line.description() + " (virtual " + line.virtualCard() + ")";
                w.text(regular, 9, MARGIN, line.date().format(DATE));
                w.text(regular, 9, 115, w.fit(regular, 9, description, RIGHT - 115 - 90));
                w.textRight(regular, 9, RIGHT, (line.credit() ? "- " : "") + money(line.amount()));
                w.down(LINE);
            }
            w.down(4);
            w.textRight(bold, 10, RIGHT, "Total  " + money(s.total()));
            w.down(24);

            w.ensureSpace(140);
            w.section("Taxas de juros e encargos");
            for (InvoiceDocuments.Rate rate : s.rates()) {
                w.text(regular, 9, MARGIN, rate.name());
                String value = percent(rate.monthly()) + (rate.yearly() == null ? "" : " ao mês  •  " + percent(rate.yearly()) + " ao ano");
                w.textRight(regular, 9, RIGHT, value);
                w.down(LINE);
            }
            w.down(6);
            for (String note : new String[]{
                    "Pagando menos que o total, o saldo vai para o rotativo na próxima fatura, com os juros acima.",
                    "O rotativo vale por um ciclo: depois, o saldo é parcelado (Res. CMN 4.549/2017).",
                    "Juros e encargos nunca passam de 100% da dívida original (Lei 14.690/2023)."}) {
                w.ensureSpace(LINE);
                w.text(regular, 8, MARGIN, note);
                w.down(12);
            }

            w.down(16);
            w.ensureSpace(30);
            w.text(regular, 8, MARGIN, "Autenticação: " + s.authenticationCode());
            w.down(12);
            w.text(regular, 8, MARGIN, "Gerado em " + s.generatedAt().format(DATE_TIME) + " — Kage (999)");
            w.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gerar o PDF da fatura", e);
        }
    }

    private static String money(BigDecimal amount) {
        // o NumberFormat põe espaço não separável depois do "R$"
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    private static String percent(BigDecimal rate) {
        return rate.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }

    private static String month(String referenceMonth) {
        return YearMonth.parse(referenceMonth).format(MONTH);
    }

    /** Escreve de cima para baixo e abre página nova quando acaba o espaço. */
    private final class Writer {

        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;
        private int pages;

        Writer(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        void newPage() throws IOException {
            if (stream != null) stream.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = HEIGHT - MARGIN;
            pages++;
            if (pages > 1) {
                text(regular, 8, MARGIN, "Kage • continuação da fatura (página " + pages + ")");
                down(24);
            }
        }

        void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) newPage();
        }

        void down(float amount) {
            y -= amount;
        }

        void text(PDType1Font font, float size, float x, String value) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, y);
            stream.showText(safe(font, value));
            stream.endText();
        }

        void textRight(PDType1Font font, float size, float right, String value) throws IOException {
            String safe = safe(font, value);
            text(font, size, right - font.getStringWidth(safe) / 1000 * size, safe);
        }

        void label(String name, String value) throws IOException {
            text(regular, 9, MARGIN, name);
            text(regular, 9, 160, value);
            down(13);
        }

        void section(String title) throws IOException {
            text(bold, 11, MARGIN, title);
            down(6);
            stream.moveTo(MARGIN, y);
            stream.lineTo(RIGHT, y);
            stream.setLineWidth(0.5f);
            stream.stroke();
            down(14);
        }

        /** Corta o texto com reticências para caber na largura. */
        String fit(PDType1Font font, float size, String value, float width) throws IOException {
            String safe = safe(font, value);
            if (font.getStringWidth(safe) / 1000 * size <= width) return safe;
            String cut = safe;
            while (cut.length() > 1 && font.getStringWidth(cut + "...") / 1000 * size > width) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "...";
        }

        void close() throws IOException {
            stream.close();
        }

        private String safe(PDType1Font font, String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                try {
                    font.encode(String.valueOf(c));
                    out.append(c);
                } catch (IOException | IllegalArgumentException e) {
                    out.append('?');
                }
            }
            return out.toString();
        }
    }
}
