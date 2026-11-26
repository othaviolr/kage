package com.kage.card.domain.service;

import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Regras de parcelamento.
 *
 * - Compra parcelada (sem juros, "parcelado lojista"): até {@link #MAX_PURCHASE_INSTALLMENTS}x.
 * - Parcelamento da fatura (com juros): de {@link #MIN_INVOICE_INSTALLMENTS} a
 *   {@link #MAX_INVOICE_INSTALLMENTS}x a {@link #INVOICE_MONTHLY_RATE} a.m. pela Tabela Price —
 *   bem mais barato que o rotativo (14% a.m.), que é o ponto de oferecê-lo.
 * - Regra do Bacen (Res. CMN 4.549/2017): o rotativo só pode ser usado por um ciclo; se o saldo
 *   do rotativo não for pago, é parcelado automaticamente em {@link #AUTO_INSTALLMENTS}x.
 *
 * Os centavos do arredondamento vão na primeira parcela, para a soma fechar exatamente.
 */
public final class InstallmentPolicy {

    public static final int MAX_PURCHASE_INSTALLMENTS = 12;
    public static final int MIN_INVOICE_INSTALLMENTS = 2;
    public static final int MAX_INVOICE_INSTALLMENTS = 12;
    public static final int AUTO_INSTALLMENTS = 6;
    public static final BigDecimal INVOICE_MONTHLY_RATE = new BigDecimal("0.0899");
    /** Diferença de arredondamento da parcela em centavos: abaixo disso, vale a taxa do contrato. */
    static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.00001");

    private InstallmentPolicy() {
    }

    /** Divide sem juros: 100 em 3x = 33,34 + 33,33 + 33,33. */
    public static List<Money> splitWithoutInterest(Money total, int installments) {
        if (installments < 1 || installments > MAX_PURCHASE_INSTALLMENTS) {
            throw new ValidationException("Compra pode ser parcelada em 1 a " + MAX_PURCHASE_INSTALLMENTS + " vezes");
        }
        if (total.amount().compareTo(new BigDecimal("0.01").multiply(BigDecimal.valueOf(installments))) < 0) {
            throw new ValidationException("Valor pequeno demais para " + installments + " parcelas");
        }
        return split(total, installments);
    }

    /** Simulação do parcelamento da fatura (Tabela Price). */
    public static Quote quoteInvoice(Money principal, int installments) {
        if (installments < MIN_INVOICE_INSTALLMENTS || installments > MAX_INVOICE_INSTALLMENTS) {
            throw new ValidationException("Fatura pode ser parcelada em " + MIN_INVOICE_INSTALLMENTS + " a "
                    + MAX_INVOICE_INSTALLMENTS + " vezes");
        }
        BigDecimal i = INVOICE_MONTHLY_RATE;
        // PMT = P * i / (1 - (1 + i)^-n)
        BigDecimal factor = BigDecimal.ONE.add(i).pow(installments, MathContext.DECIMAL64);
        BigDecimal pmt = principal.amount().multiply(i).multiply(factor)
                .divide(factor.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP);
        Money total = Money.of(pmt.multiply(BigDecimal.valueOf(installments)));
        return new Quote(installments, principal, total, total.subtract(principal), INVOICE_MONTHLY_RATE, split(total, installments));
    }

    /** Mesma simulação, com os juros cortados para caber no teto legal ({@code maxInterest}). */
    public static Quote capInterest(Quote quote, Money maxInterest) {
        if (!quote.interest().isGreaterThan(maxInterest)) return quote;
        Money total = quote.principal().add(maxInterest);
        return new Quote(quote.installments(), quote.principal(), total, maxInterest, quote.monthlyRate(),
                split(total, quote.installments()));
    }

    /**
     * Quanto do principal cada parcela amortiza (Price: os juros saem do saldo, o resto amortiza). A
     * taxa é a implícita nas parcelas, então vale também quando o teto legal cortou os juros. A última
     * leva o que sobrar, para a soma fechar no principal.
     */
    public static List<Money> amortizations(Quote quote) {
        Money sample = quote.amounts().get(quote.installments() - 1); // a 1ª carrega os centavos
        BigDecimal implied = AnticipationPolicy.impliedMonthlyRate(quote.principal(), sample, quote.installments());
        // a implícita sai um fio abaixo da do contrato pelo arredondamento da parcela: só vale se o teto cortou os juros
        BigDecimal rate = implied.compareTo(quote.monthlyRate().subtract(RATE_TOLERANCE)) >= 0 ? quote.monthlyRate() : implied;
        BigDecimal balance = quote.principal().amount();
        List<Money> result = new ArrayList<>(quote.installments());
        for (int k = 0; k < quote.installments(); k++) {
            BigDecimal amortization;
            if (k == quote.installments() - 1) {
                amortization = balance;
            } else {
                BigDecimal interest = balance.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                amortization = quote.amounts().get(k).amount().subtract(interest).max(BigDecimal.ZERO).min(balance);
            }
            result.add(Money.of(amortization));
            balance = balance.subtract(amortization);
        }
        return result;
    }

    private static List<Money> split(Money total, int installments) {
        BigDecimal base = total.amount().divide(BigDecimal.valueOf(installments), 2, RoundingMode.DOWN);
        BigDecimal remainder = total.amount().subtract(base.multiply(BigDecimal.valueOf(installments)));
        List<Money> parts = new ArrayList<>(installments);
        parts.add(Money.of(base.add(remainder)));
        for (int k = 1; k < installments; k++) parts.add(Money.of(base));
        return parts;
    }

    /** @param amounts valor de cada parcela, na ordem (a 1ª carrega os centavos do arredondamento) */
    public record Quote(int installments, Money principal, Money total, Money interest, BigDecimal monthlyRate,
                        List<Money> amounts) {}
}
