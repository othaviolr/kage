package com.kage.savings.application.usecase;

import com.kage.shared.domain.valueobject.ReceiptCode;

import java.util.UUID;

/**
 * Informe de rendimentos em PDF: o mesmo conteúdo do {@link GetIncomeReport}, num documento para guardar
 * ou mandar ao contador, com código de autenticação (o banco recalcula a partir dos números: um PDF com
 * valor editado não bate). O ano corrente sai marcado como parcial.
 */
public class IncomeReportDocuments {

    public record Document(String fileName, byte[] content) {}

    /** Desenha o documento. A implementação (PDFBox) fica na infraestrutura. */
    public interface Renderer {
        byte[] render(GetIncomeReport.Output report, String authenticationCode);
    }

    private final GetIncomeReport getIncomeReport;
    private final Renderer renderer;

    public IncomeReportDocuments(GetIncomeReport getIncomeReport, Renderer renderer) {
        this.getIncomeReport = getIncomeReport;
        this.renderer = renderer;
    }

    public Document pdf(UUID customerId, int year) {
        GetIncomeReport.Output report = getIncomeReport.execute(new GetIncomeReport.Input(customerId, year));
        String code = authenticationCode(customerId, report);
        return new Document("informe-de-rendimentos-kage-" + year + (report.partial() ? "-parcial" : "") + ".pdf",
                renderer.render(report, code));
    }

    /** Quem, qual ano, até quando e os totais que vão para a declaração. */
    static String authenticationCode(UUID customerId, GetIncomeReport.Output r) {
        GetIncomeReport.Totals t = r.totals();
        java.math.BigDecimal assets = r.accounts().stream().map(GetIncomeReport.AccountLine::balanceYearEnd)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .add(r.savingsBoxes().stream().map(GetIncomeReport.SavingsLine::balanceYearEnd)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        return ReceiptCode.of("INFORME", customerId, r.year(), r.yearEnd(), assets.stripTrailingZeros().toPlainString(),
                t.exclusiveTaxationIncome().stripTrailingZeros().toPlainString(),
                t.incomeTaxWithheld().stripTrailingZeros().toPlainString(),
                t.debtYearEnd().stripTrailingZeros().toPlainString());
    }
}
