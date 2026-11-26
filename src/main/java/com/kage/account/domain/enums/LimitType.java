package com.kage.account.domain.enums;

import com.kage.shared.domain.valueobject.Money;

/** Os limites que o cliente ajusta, com o teto de cada um (acima disso, só com análise do banco). */
public enum LimitType {
    PIX_DAILY("limite diário de PIX", "20000.00"),
    PIX_NIGHT("limite noturno de PIX (20h às 6h)", "5000.00"),
    DAILY_WITHDRAWAL("limite diário de saque", "5000.00"),
    DAILY_TRANSFER("limite diário de transferência", "50000.00"),
    MONTHLY_TRANSFER("limite mensal de transferência", "200000.00");

    private final String label;
    private final Money ceiling;

    LimitType(String label, String ceiling) {
        this.label = label;
        this.ceiling = Money.of(ceiling);
    }

    public String label() { return label; }
    public Money ceiling() { return ceiling; }
}
