package com.kage.savings.application.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura do informe de rendimentos. É um relatório (lado de leitura): junta dados de cliente, conta
 * e caixinhas de uma vez, em vez de atravessar as portas de cada contexto lançamento a lançamento.
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

    Optional<Holder> holder(UUID customerId);

    /** Contas que existiam em algum momento do período [{@code from}, {@code to}). */
    List<AccountBalances> accounts(UUID customerId, LocalDateTime from, LocalDateTime to);

    /** Caixinhas que existiam em algum momento do período [{@code from}, {@code to}). */
    List<BoxFigures> savingsBoxes(UUID customerId, LocalDateTime from, LocalDateTime to);
}
