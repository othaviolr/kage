package com.kage.savings.application.usecase;

import com.kage.savings.application.query.IncomeReportSource;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Informe de rendimentos do ano-calendário, no formato que a declaração do IR pede:
 *
 * <ul>
 *   <li><b>Bens e Direitos</b>: saldo em 31/12 do ano anterior e do ano — conta (grupo 06, código 01,
 *       depósito em conta) e caixinhas (grupo 04, código 02, aplicação de renda fixa).</li>
 *   <li><b>Rendimentos sujeitos à tributação exclusiva/definitiva</b> (item 06, aplicações
 *       financeiras): rendimento realizado nos resgates do ano, líquido de IOF e do IR já retido.</li>
 * </ul>
 *
 * Rendimento ainda não resgatado não entra como rendimento: só aparece no saldo de Bens e Direitos
 * (renda fixa é tributada no resgate). Do ano corrente, o informe sai parcial (até hoje).
 */
public class GetIncomeReport {

    static final int FIRST_YEAR = 2000;

    public record Input(UUID customerId, int year) {}

    public record AccountLine(String branch, String number, String type, BigDecimal balancePreviousYearEnd,
                              BigDecimal balanceYearEnd) {}

    public record SavingsLine(UUID boxId, String name, String status, BigDecimal balancePreviousYearEnd,
                              BigDecimal balanceYearEnd, BigDecimal realizedGain, BigDecimal iofWithheld,
                              BigDecimal incomeTaxWithheld, BigDecimal netIncome) {}

    public record Totals(BigDecimal realizedGain, BigDecimal iofWithheld, BigDecimal incomeTaxWithheld,
                         BigDecimal exclusiveTaxationIncome) {}

    /** Onde cada número entra na declaração. */
    public record DeclarationGuide(String assets, String exclusiveTaxation) {}

    public record Output(int year, boolean partial, String holderName, String holderCpf, LocalDate previousYearEnd,
                         LocalDate yearEnd, List<AccountLine> accounts, List<SavingsLine> savingsBoxes, Totals totals,
                         DeclarationGuide guide, LocalDateTime generatedAt) {}

    private final IncomeReportSource source;
    private final Clock clock;

    public GetIncomeReport(IncomeReportSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public Output execute(Input input) {
        LocalDate today = LocalDate.now(clock);
        int year = input.year();
        if (year < FIRST_YEAR || year > today.getYear()) {
            throw new ValidationException("Ano do informe deve estar entre " + FIRST_YEAR + " e " + today.getYear());
        }
        IncomeReportSource.Holder holder = source.holder(input.customerId())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        // cortes: começo do ano (= fim de 31/12 anterior) e começo do ano seguinte, ou agora no ano corrente
        LocalDateTime from = LocalDate.of(year, 1, 1).atStartOfDay();
        boolean partial = year == today.getYear();
        LocalDateTime to = partial ? LocalDateTime.now(clock) : LocalDate.of(year + 1, 1, 1).atStartOfDay();

        List<AccountLine> accounts = source.accounts(input.customerId(), from, to).stream()
                .map(a -> new AccountLine(a.branch(), a.number(), a.type(), a.balanceBefore(), a.balanceAtCutoff()))
                .toList();
        List<SavingsLine> boxes = source.savingsBoxes(input.customerId(), from, to).stream()
                .map(b -> new SavingsLine(b.boxId(), b.name(), b.status(), b.balanceBefore(), b.balanceAtCutoff(),
                        b.realizedGain(), b.iof(), b.incomeTax(), b.realizedGain().subtract(b.iof()).subtract(b.incomeTax())))
                .toList();

        Totals totals = new Totals(
                sum(boxes.stream().map(SavingsLine::realizedGain).toList()),
                sum(boxes.stream().map(SavingsLine::iofWithheld).toList()),
                sum(boxes.stream().map(SavingsLine::incomeTaxWithheld).toList()),
                sum(boxes.stream().map(SavingsLine::netIncome).toList()));

        DeclarationGuide guide = new DeclarationGuide(
                "Bens e Direitos: conta corrente no grupo 06 (Depósitos à vista e numerário), código 01; cada caixinha "
                        + "no grupo 04 (Aplicações e investimentos), código 02 (renda fixa), informando o saldo em 31/12 "
                        + "de cada ano. Instituição: Kage.",
                "Rendimentos Sujeitos à Tributação Exclusiva/Definitiva, item 06 (Rendimentos de aplicações "
                        + "financeiras): R$ " + totals.exclusiveTaxationIncome().toPlainString()
                        + ". O IR já foi retido na fonte (R$ " + totals.incomeTaxWithheld().toPlainString()
                        + "): não há imposto a pagar sobre esse valor.");

        return new Output(year, partial, holder.fullName(), formatCpf(holder.cpf()), LocalDate.of(year - 1, 12, 31),
                partial ? today : LocalDate.of(year, 12, 31), accounts, boxes, totals, guide, LocalDateTime.now(clock));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    static String formatCpf(String cpf) {
        String digits = cpf.replaceAll("\\D", "");
        if (digits.length() != 11) return cpf;
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-" + digits.substring(9);
    }
}
