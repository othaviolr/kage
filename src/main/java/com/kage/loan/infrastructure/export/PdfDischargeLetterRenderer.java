package com.kage.loan.infrastructure.export;

import com.kage.loan.application.usecase.LoanDischargeLetters;
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

import static com.kage.shared.infrastructure.pdf.PdfPageWriter.MARGIN;
import static com.kage.shared.infrastructure.pdf.PdfPageWriter.RIGHT;
import static com.kage.shared.infrastructure.pdf.PdfPageWriter.money;

/** Carta de quitação em PDF: a declaração, os dados do contrato e o código de autenticação. */
public class PdfDischargeLetterRenderer implements LoanDischargeLetters.Renderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Coluna dos valores: depois do rótulo mais longo ("Empréstimo renegociado"). */
    private static final float VALUE_X = 180;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Override
    public byte[] render(LoanDischargeLetters.Letter l) {
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Carta de quitação Kage");
            info.setAuthor("Kage");
            info.setCreationDate(GregorianCalendar.from(l.generatedAt().atZone(ZoneId.systemDefault())));

            PdfPageWriter w = new PdfPageWriter(document, "continuação da carta de quitação");
            PDType1Font regular = w.regular;
            PDType1Font bold = w.bold;

            w.text(bold, 20, MARGIN, "Kage");
            w.textRight(bold, 12, RIGHT, "Carta de quitação");
            w.down(40);

            String paidOff = l.paidOffAt().toLocalDate().format(DATE);
            String what = l.agreement()
                    ? "o acordo de renegociação firmado em " + l.contractDate().format(DATE)
                    + (l.originalContractDate() == null ? ""
                    : ", que substituiu o empréstimo pessoal contratado em " + l.originalContractDate().format(DATE) + ",")
                    : "o empréstimo pessoal contratado em " + l.contractDate().format(DATE) + ",";
            String rest = l.agreement()
                    ? " não restando nenhum valor em aberto referente ao acordo nem ao empréstimo renegociado."
                    : " não restando nenhum valor em aberto referente a este contrato.";
            w.paragraph(regular, 11, MARGIN, RIGHT - MARGIN, 16, "O Kage (código 999) declara, para os devidos fins, que "
                    + what + " em nome de " + l.holderName() + ", CPF " + l.holderCpf() + ", foi integralmente quitado em "
                    + paidOff + "," + rest);
            w.down(20);

            w.section("Dados do contrato");
            w.label("Titular", l.holderName(), VALUE_X);
            w.label("CPF", l.holderCpf(), VALUE_X);
            w.label("Contrato", l.loanId().toString(), VALUE_X);
            w.label(l.agreement() ? "Acordo firmado em" : "Contratado em", l.contractDate().format(DATE), VALUE_X);
            if (l.originalContractDate() != null) w.label("Empréstimo renegociado", "contratado em " + l.originalContractDate().format(DATE), VALUE_X);
            w.label(l.agreement() ? "Valor renegociado" : "Valor financiado", money(l.financed()) + " (IOF incluso)", VALUE_X);
            w.label("Parcelas", String.valueOf(l.installments()), VALUE_X);
            if (l.downPayment() != null && l.downPayment().signum() > 0) w.label("Entrada do acordo", money(l.downPayment()), VALUE_X);
            w.label("Pago nas parcelas", money(l.paidInInstallments()) + " (com descontos e encargos)", VALUE_X);
            w.label("Quitado em", paidOff, VALUE_X);
            w.down(30);

            w.paragraph(regular, 8, MARGIN, RIGHT - MARGIN, 11, "Guarde esta carta: ela comprova que a dívida acabou. "
                    + "Se o contrato aparecer como pendente em algum cadastro de crédito, ela serve para pedir a correção.");
            w.down(16);
            w.text(regular, 8, MARGIN, "Autenticação: " + l.authenticationCode());
            w.down(12);
            w.text(regular, 8, MARGIN, "Gerado em " + l.generatedAt().format(DATE_TIME) + " — Kage (999)");
            w.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gerar a carta de quitação", e);
        }
    }
}
