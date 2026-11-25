package com.kage.savings.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tributação de renda fixa no resgate, sobre o RENDIMENTO (nunca sobre o que foi guardado), contada
 * em dias corridos desde o depósito de cada lote:
 *
 * <ul>
 *   <li><b>IOF</b> (Decreto 6.306/2007): regressivo nos primeiros 30 dias, de 96% do rendimento no
 *       1º dia a zero no 30º.</li>
 *   <li><b>IR</b> (Lei 11.033/2004): tabela regressiva — 22,5% até 180 dias, 20% até 360, 17,5% até
 *       720, 15% acima — sobre o rendimento já descontado o IOF.</li>
 * </ul>
 *
 * Arredondamento dos impostos para baixo, no centavo (a favor do cliente).
 */
public final class SavingsTaxPolicy {

    /** % do rendimento retido de IOF por dia corrido (índice = dias; 30 em diante = 0). */
    private static final int[] IOF_PERCENT = {
            100, 96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 56, 53, 50,
            46, 43, 40, 36, 33, 30, 26, 23, 20, 16, 13, 10, 6, 3
    };

    public record Taxes(BigDecimal iof, BigDecimal incomeTax) {
        public BigDecimal total() {
            return iof.add(incomeTax);
        }
    }

    private SavingsTaxPolicy() {
    }

    public static BigDecimal iofRate(long daysHeld) {
        if (daysHeld >= IOF_PERCENT.length) return BigDecimal.ZERO;
        return BigDecimal.valueOf(IOF_PERCENT[(int) Math.max(daysHeld, 0)], 2);
    }

    public static BigDecimal incomeTaxRate(long daysHeld) {
        if (daysHeld <= 180) return new BigDecimal("0.225");
        if (daysHeld <= 360) return new BigDecimal("0.200");
        if (daysHeld <= 720) return new BigDecimal("0.175");
        return new BigDecimal("0.150");
    }

    public static Taxes on(BigDecimal gain, long daysHeld) {
        if (gain.signum() <= 0) return new Taxes(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        BigDecimal iof = gain.multiply(iofRate(daysHeld)).setScale(2, RoundingMode.DOWN);
        BigDecimal incomeTax = gain.subtract(iof).multiply(incomeTaxRate(daysHeld)).setScale(2, RoundingMode.DOWN);
        return new Taxes(iof, incomeTax);
    }
}
