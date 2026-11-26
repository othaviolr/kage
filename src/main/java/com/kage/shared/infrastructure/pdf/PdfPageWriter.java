package com.kage.shared.infrastructure.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Escreve um documento A4 do PDFBox de cima para baixo e abre página nova quando acaba o espaço. Fontes
 * padrão (Helvetica, sem embutir arquivo de fonte); caractere que a codificação da fonte não tem vira
 * "?" em vez de derrubar a geração. Usado pela fatura e pelo informe de rendimentos.
 */
public final class PdfPageWriter implements AutoCloseable {

    public static final float MARGIN = 50;
    public static final float WIDTH = PDRectangle.A4.getWidth();
    public static final float HEIGHT = PDRectangle.A4.getHeight();
    public static final float RIGHT = WIDTH - MARGIN;
    public static final float LINE = 15;

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    public final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private final PDDocument document;
    /** Cabeçalho das páginas seguintes: "Kage • {continuation} (página N)". */
    private final String continuation;
    private PDPageContentStream stream;
    private float y;
    private int pages;

    public PdfPageWriter(PDDocument document, String continuation) throws IOException {
        this.document = document;
        this.continuation = continuation;
        newPage();
    }

    public static String money(BigDecimal amount) {
        // o NumberFormat põe espaço não separável depois do "R$"
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    public void newPage() throws IOException {
        if (stream != null) stream.close();
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        stream = new PDPageContentStream(document, page);
        y = HEIGHT - MARGIN;
        pages++;
        if (pages > 1) {
            text(regular, 8, MARGIN, "Kage • " + continuation + " (página " + pages + ")");
            down(24);
        }
    }

    public void ensureSpace(float needed) throws IOException {
        if (y - needed < MARGIN) newPage();
    }

    public void down(float amount) {
        y -= amount;
    }

    public void text(PDType1Font font, float size, float x, String value) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(safe(font, value));
        stream.endText();
    }

    public void textRight(PDType1Font font, float size, float right, String value) throws IOException {
        String safe = safe(font, value);
        text(font, size, right - width(font, size, safe), safe);
    }

    public void label(String name, String value) throws IOException {
        label(name, value, 160);
    }

    public void label(String name, String value, float valueX) throws IOException {
        text(regular, 9, MARGIN, name);
        text(regular, 9, valueX, value);
        down(13);
    }

    public void section(String title) throws IOException {
        text(bold, 11, MARGIN, title);
        down(6);
        stream.moveTo(MARGIN, y);
        stream.lineTo(RIGHT, y);
        stream.setLineWidth(0.5f);
        stream.stroke();
        down(14);
    }

    /** Texto corrido quebrado em linhas que cabem na largura (abre página nova se precisar). */
    public void paragraph(PDType1Font font, float size, float x, float width, float lineHeight, String value) throws IOException {
        for (String line : wrap(font, size, value, width)) {
            ensureSpace(lineHeight);
            text(font, size, x, line);
            down(lineHeight);
        }
    }

    /** Quebra por palavras para caber em {@code width}; "R$" não se separa do valor. */
    public List<String> wrap(PDType1Font font, float size, String value, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String glued : safe(font, value).replace("R$ ", "R$\u0000").split(" ")) {
            String word = glued.replace('\u0000', ' ');
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && width(font, size, candidate) > width) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    /** Corta o texto com reticências para caber na largura. */
    public String fit(PDType1Font font, float size, String value, float width) throws IOException {
        String safe = safe(font, value);
        if (width(font, size, safe) <= width) return safe;
        String cut = safe;
        while (cut.length() > 1 && width(font, size, cut + "...") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }

    private static float width(PDType1Font font, float size, String value) throws IOException {
        return font.getStringWidth(value) / 1000 * size;
    }

    private static String safe(PDType1Font font, String value) {
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
