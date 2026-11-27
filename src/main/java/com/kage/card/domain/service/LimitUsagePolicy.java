package com.kage.card.domain.service;

import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Faixas de uso do limite que geram aviso ao cliente: 80% (o limite está acabando) e 100% (novas
 * compras vão ser recusadas). O uso é a dívida no cartão (compras, parcelas futuras, encargos) sobre o
 * limite em uso — o que o cliente escolheu, não o aprovado.
 */
public final class LimitUsagePolicy {

    public static final int WARNING = 80;
    public static final int EXHAUSTED = 100;
    private static final List<Integer> THRESHOLDS = List.of(WARNING, EXHAUSTED);

    private LimitUsagePolicy() {
    }

    /** Percentual em uso, arredondado para baixo: R$ 799,99 de R$ 1.000 é 79%, não 80%. */
    public static int usagePercent(Money used, Money limit) {
        if (limit.isZero()) return EXHAUSTED;
        return used.amount().multiply(BigDecimal.valueOf(100)).divide(limit.amount(), 0, RoundingMode.DOWN).intValue();
    }

    /** Faixas já atingidas com esse uso, da menor para a maior (vazio abaixo de 80%). */
    public static List<Integer> reached(Money used, Money limit) {
        int percent = usagePercent(used, limit);
        return THRESHOLDS.stream().filter(t -> percent >= t).toList();
    }
}
