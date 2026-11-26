package com.kage.savings.application.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura do informe de rendimentos. É um relatório (lado de leitura): junta dados de cliente, conta
 * caixinhas e empréstimos de uma vez, em vez de atravessar as portas de cada contexto lançamento a lançamento.
 * Nenhuma escrita passa por aqui.
 */
public interface IncomeReportSource {

    record Holder(String fullName, String cpf) {}

    /** Saldos da conta pelo ledger: o balance_after do último lançamento antes de cada corte. */
    record AccountBalances(UUID accountId, String branch, String number, String type,
                           BigDecimal balanceBefore, BigDecimal balanceAtCutoff) {}

    /** Saldos da caixinha nos cortes e o que os resgates entre eles realizaram. */
    record BoxFigures(UUID boxId, String name, String status, BigDecimal balanceBefore, BigDecimal balanceAtCutoff,
                      BigDecimal realizedGain, BigDecimal iof, BigDecimal incomeTax) {}

    /**
     * Empréstimo nos cortes: saldo devedor = principal das parcelas ainda não pagas em cada corte (o que
     * a amortização da Price ainda não abateu), e o que foi pago entre os cortes (com encargos e descontos).
     */
    record LoanFigures(UUID loanId, LocalDate contractDate, String status, BigDecimal financed, int installments,
                       BigDecimal installmentAmount, BigDecimal balanceBefore, BigDecimal balanceAtCutoff,
                       BigDecimal paidInPeriod) {}

    Optional<Holder> holder(UUID customerId);

    /** Contas que existiam em algum momento do período [{@code from}, {@code to}). */
    List<AccountBalances> accounts(UUID customerId, LocalDateTime from, LocalDateTime to);

    /** Caixinhas que existiam em algum momento do período [{@code from}, {@code to}). */
    List<BoxFigures> savingsBoxes(UUID customerId, LocalDateTime from, LocalDateTime to);

    /**
     * Empréstimos contratados antes de {@code to} e ainda não encerrados em {@code from}. Os cancelados
     * por arrependimento ficam de fora: o contrato foi desfeito e não houve dívida.
     */
    List<LoanFigures> loans(UUID customerId, LocalDateTime from, LocalDateTime to);
}
