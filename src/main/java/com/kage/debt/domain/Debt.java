package com.kage.debt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma dívida do cliente no Kage, como as fontes (cartão, empréstimo) a enxergam hoje. O texto para o
 * cliente sai do use case.
 *
 * @param referenceId       o que identifica a dívida na API de origem: fatura, plano de parcelas ou empréstimo
 * @param cardId            dívida do cartão: qual; nulo para empréstimo
 * @param outstanding       o que falta pagar seguindo o plano (valor de face: fatura, parcelas em aberto)
 * @param payoffToday       quanto custa quitar tudo hoje (com o desconto dos juros futuros, ou com multa e mora)
 * @param nextDueDate       próximo vencimento; nulo se não há
 * @param remainingInstallments parcelas que faltam; nulo quando não é parcelado
 * @param monthlyRate       juros ao mês (fração); zero = sem juros correndo
 * @param cetYearly         Custo Efetivo Total ao ano (fração); nulo quando não há juros
 * @param overdue           há valor vencido e não pago
 * @param minimumPayment    fatura fechada: o mínimo; nulo nas outras
 * @param closingDate       fatura atual: quando fecha; nulo nas outras
 * @param overdueInstallments empréstimo: quantas parcelas vencidas; zero nas outras
 */
public record Debt(DebtKind kind, UUID referenceId, UUID cardId, String description, BigDecimal outstanding,
                   BigDecimal payoffToday, LocalDate nextDueDate, BigDecimal nextAmount, Integer remainingInstallments,
                   BigDecimal monthlyRate, BigDecimal cetYearly, boolean overdue, BigDecimal minimumPayment,
                   LocalDate closingDate, int overdueInstallments) {

    public boolean hasInterest() {
        return monthlyRate != null && monthlyRate.signum() > 0;
    }
}
