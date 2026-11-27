package com.kage.card.domain.service;

import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Set;

/**
 * Compra em moeda estrangeira no cartão de crédito.
 *
 * <ul>
 *   <li><b>Conversão na data da compra</b>: cotação de venda (PTAX) do dia, mais o spread do Kage. O
 *       valor em reais fica fixo na fatura — não muda até o pagamento.</li>
 *   <li><b>IOF de {@value #IOF_PERCENT}%</b> sobre o valor convertido (Decreto 6.306/2007, art. 15-B,
 *       na redação de 2025), numa linha própria da fatura.</li>
 *   <li><b>À vista</b>: compra internacional não parcela.</li>
 * </ul>
 */
public final class InternationalPurchasePolicy {

    public static final String IOF_PERCENT = "3.5";
    public static final BigDecimal IOF_RATE = new BigDecimal("0.035");
    /** Spread do Kage sobre a PTAX. */
    public static final BigDecimal SPREAD = new BigDecimal("0.04");
    public static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP");

    /**
     * @param rate  o que o cliente paga por unidade: PTAX × (1 + spread)
     * @param brl   valor da compra em reais
     * @param total compra + IOF: o que consome do limite
     */
    public record Quote(String currency, BigDecimal foreignAmount, BigDecimal ptax, LocalDate ptaxDate,
                        BigDecimal spread, BigDecimal rate, BigDecimal brl, BigDecimal iof, BigDecimal total) {}

    private InternationalPurchasePolicy() {
    }

    /** Moeda da compra em maiúsculas; nula ou BRL = compra em reais (devolve nulo). */
    public static String currencyOf(String raw) {
        if (raw == null) return null;
        String currency = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("BRL".equals(currency)) return null;
        if (!CURRENCIES.contains(currency)) {
            throw new ValidationException("Moeda não aceita: use BRL, USD, EUR ou GBP");
        }
        return currency;
    }

    public static Quote quote(String currency, BigDecimal foreignAmount, ExchangeRateProvider.Rate ptax) {
        if (foreignAmount == null || foreignAmount.signum() <= 0) throw new ValidationException("Valor deve ser maior que zero");
        BigDecimal amount = foreignAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = ptax.rate().multiply(BigDecimal.ONE.add(SPREAD)).setScale(4, RoundingMode.HALF_UP);
        BigDecimal brl = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal iof = brl.multiply(IOF_RATE).setScale(2, RoundingMode.HALF_UP);
        return new Quote(currency, amount, ptax.rate(), ptax.date(), SPREAD, rate, brl, iof, brl.add(iof));
    }
}
