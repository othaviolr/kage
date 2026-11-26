package com.kage.savings.infrastructure.report;

import com.kage.savings.application.usecase.GetIncomeReport;
import com.kage.savings.application.usecase.IncomeReportDocuments;
import com.kage.shared.infrastructure.pdf.PdfPageWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;

import static com.kage.shared.infrastructure.pdf.PdfPageWriter.LINE;
import static com.kage.shared.infrastructure.pdf.PdfPageWriter.MARGIN;
import static com.kage.shared.infrastructure.pdf.PdfPageWriter.RIGHT;
import static com.kage.shared.infrastructure.pdf.PdfPageWriter.money;

/**
 * Informe de rendimentos em PDF, na ordem da declaração: Bens e Direitos, Rendimentos de Tributação
 * Exclusiva, Dívidas e Ônus Reais e, no fim, como declarar cada número.
 */
public class PdfIncomeReportRenderer implements IncomeReportDocuments.Renderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final float SMALL = 13;

    @Override
    public byte[] render(GetIncomeReport.Output r, String authenticationCode) {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Informe de rendimentos Kage " + r.year());
            info.setAuthor("Kage");
            info.setCreationDate(GregorianCalendar.from(r.generatedAt().atZone(ZoneId.systemDefault())));

            PdfPageWriter w = new PdfPageWriter(document, "continuação do informe de rendimentos " + r.year());
            PDType1Font regular = w.regular;
            PDType1Font bold = w.bold;

            w.text(bold, 20, MARGIN, "Kage");
            w.textRight(bold, 12, RIGHT, "Informe de rendimentos " + r.year());
            w.down(22);
            w.text(regular, 11, MARGIN, "Ano-calendário " + r.year() + "  •  declaração de " + (r.year() + 1));
            w.down(LINE);
            if (r.partial()) {
                w.text(bold, 9, MARGIN, "PARCIAL: valores até " + r.yearEnd().format(DATE)
                        + ". O ano ainda não acabou: não use este documento na declaração.");
                w.down(LINE);
            }
            w.down(10);
            w.label("Titular", r.holderName(), 140);
            w.label("CPF", r.holderCpf(), 140);
            w.label("Instituição", "Kage (código 999)", 140);
            w.down(14);

            String previous = r.previousYearEnd().format(DATE);
            String current = r.yearEnd().format(DATE);

            // Bens e Direitos
            w.section("Bens e Direitos");
            w.text(bold, 8, MARGIN, "Descrição");
            w.text(bold, 8, 300, "Grupo / código");
            w.textRight(bold, 8, 460, "Saldo em " + previous);
            w.textRight(bold, 8, RIGHT, "Saldo em " + current);
            w.down(SMALL);
            if (r.accounts().isEmpty() && r.savingsBoxes().isEmpty()) {
                w.text(regular, 9, MARGIN, "Nenhuma conta ou caixinha no período.");
                w.down(SMALL);
            }
            for (GetIncomeReport.AccountLine a : r.accounts()) {
                w.ensureSpace(SMALL);
                String kind = "SAVINGS".equals(a.type()) ? "Conta poupança" : "Conta corrente";
                w.text(regular, 9, MARGIN, w.fit(regular, 9, kind + " — ag. " + a.branch() + ", conta " + a.number(), 240));
                w.text(regular, 9, 300, "06 / 01");
                w.textRight(regular, 9, 460, money(a.balancePreviousYearEnd()));
                w.textRight(regular, 9, RIGHT, money(a.balanceYearEnd()));
                w.down(SMALL);
            }
            for (GetIncomeReport.SavingsLine b : r.savingsBoxes()) {
                w.ensureSpace(SMALL);
                String name = "Caixinha \"" + b.name() + "\"" + ("CLOSED".equals(b.status()) ? " (encerrada)" : "");
                w.text(regular, 9, MARGIN, w.fit(regular, 9, name, 240));
                w.text(regular, 9, 300, "04 / 02");
                w.textRight(regular, 9, 460, money(b.balancePreviousYearEnd()));
                w.textRight(regular, 9, RIGHT, money(b.balanceYearEnd()));
                w.down(SMALL);
            }
            w.down(16);

            // Rendimentos sujeitos à tributação exclusiva
            w.ensureSpace(80);
            w.section("Rendimentos sujeitos à tributação exclusiva/definitiva");
            w.text(bold, 8, MARGIN, "Caixinha");
            w.textRight(bold, 8, 300, "Rendimento");
            w.textRight(bold, 8, 380, "IOF");
            w.textRight(bold, 8, 460, "IR retido");
            w.textRight(bold, 8, RIGHT, "Líquido");
            w.down(SMALL);
            boolean anyIncome = false;
            for (GetIncomeReport.SavingsLine b : r.savingsBoxes()) {
                if (b.realizedGain().signum() == 0) continue;
                anyIncome = true;
                w.ensureSpace(SMALL);
                w.text(regular, 9, MARGIN, w.fit(regular, 9, b.name(), 170));
                w.textRight(regular, 9, 300, money(b.realizedGain()));
                w.textRight(regular, 9, 380, money(b.iofWithheld()));
                w.textRight(regular, 9, 460, money(b.incomeTaxWithheld()));
                w.textRight(regular, 9, RIGHT, money(b.netIncome()));
                w.down(SMALL);
            }
            if (!anyIncome) {
                w.text(regular, 9, MARGIN, "Nenhum resgate com rendimento no período.");
                w.down(SMALL);
            }
            GetIncomeReport.Totals t = r.totals();
            w.down(2);
            w.text(bold, 9, MARGIN, "Total (item 06 — aplicações financeiras)");
            w.textRight(bold, 9, 300, money(t.realizedGain()));
            w.textRight(bold, 9, 380, money(t.iofWithheld()));
            w.textRight(bold, 9, 460, money(t.incomeTaxWithheld()));
            w.textRight(bold, 9, RIGHT, money(t.exclusiveTaxationIncome()));
            w.down(24);

            // Dívidas e Ônus Reais
            w.ensureSpace(80);
            w.section("Dívidas e Ônus Reais");
            if (r.debts().isEmpty()) {
                w.text(regular, 9, MARGIN, "Nenhum empréstimo no período.");
                w.down(SMALL);
            }
            for (GetIncomeReport.DebtLine d : r.debts()) {
                w.ensureSpace(4 * SMALL);
                w.paragraph(regular, 9, MARGIN, RIGHT - MARGIN, 12, d.description());
                w.text(regular, 8, MARGIN, "Código 11");
                w.text(regular, 8, 110, "Saldo em " + previous + ": " + money(d.balancePreviousYearEnd()));
                w.text(regular, 8, 265, "Saldo em " + current + ": " + money(d.balanceYearEnd()));
                w.text(regular, 8, 420, "Pago no ano: " + money(d.paidInYear()));
                w.down(12);
                w.text(bold, 8, MARGIN, d.mustDeclare() ? "Declarar" : "Dispensado de declarar (até R$ 5.000,00)");
                w.down(18);
            }
            if (!r.debts().isEmpty()) {
                w.text(bold, 9, MARGIN, "Total devedor em " + current + ": " + money(t.debtYearEnd())
                        + "   •   pago no ano: " + money(t.debtPaidInYear()));
                w.down(SMALL);
            }
            w.down(16);

            // como declarar
            w.ensureSpace(80);
            w.section("Como declarar");
            guide(w, r.guide().assets());
            guide(w, r.guide().exclusiveTaxation());
            guide(w, r.guide().debts());

            w.down(16);
            w.ensureSpace(30);
            w.text(regular, 8, MARGIN, "Autenticação: " + authenticationCode);
            w.down(12);
            w.text(regular, 8, MARGIN, "Gerado em " + r.generatedAt().format(DATE_TIME) + " — Kage (999)");
            w.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gerar o PDF do informe de rendimentos", e);
        }
    }

    private static void guide(PdfPageWriter w, String text) throws IOException {
        w.paragraph(w.regular, 8, MARGIN, RIGHT - MARGIN, 11, text);
        w.down(5);
    }
}
