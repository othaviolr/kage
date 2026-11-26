package com.kage.account.domain.enums;

import com.kage.shared.domain.valueobject.Money;

/**
 * Os limites que o cliente ajusta, com o piso e o teto de cada um (acima do teto, só com análise do
 * banco). O início da janela noturna do PIX entra aqui também: começar mais tarde deixa a conta mais
 * tempo exposta ao limite diurno, então segue a mesma regra de um aumento (vale 24h depois).
 */
public enum LimitType {
    PIX_DAILY("limite diário de PIX", "0", "20000.00", false),
    PIX_NIGHT("limite noturno de PIX", "0", "5000.00", false),
    DAILY_WITHDRAWAL("limite diário de saque", "0", "5000.00", false),
    DAILY_TRANSFER("limite diário de transferência", "0", "50000.00", false),
    MONTHLY_TRANSFER("limite mensal de transferência", "0", "200000.00", false),
    /** Hora em que a janela noturna começa (20 a 22); o fim é sempre às 6h. */
    PIX_NIGHT_START("início da janela noturna do PIX", "20", "22", true);

    private final String label;
    private final Money floor;
    private final Money ceiling;
    private final boolean hour;

    LimitType(String label, String floor, String ceiling, boolean hour) {
        this.label = label;
        this.floor = Money.of(floor);
        this.ceiling = Money.of(ceiling);
        this.hour = hour;
    }

    public String label() { return label; }
    public Money floor() { return floor; }
    public Money ceiling() { return ceiling; }
    /** É uma hora cheia, não um valor em reais. */
    public boolean isHour() { return hour; }
}
