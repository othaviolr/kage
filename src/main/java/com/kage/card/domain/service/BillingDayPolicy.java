package com.kage.card.domain.service;

/**
 * Troca do dia de vencimento da fatura. O cliente escolhe o vencimento (1 a 28); o fechamento fica
 * {@value #DAYS_BEFORE_DUE} dias antes, como no cartão padrão do Kage (fecha dia 10, vence dia 20).
 * Quando a conta cai em 29, 30 ou 31 (que não existem em todo mês), o fechamento fica no dia 28 — o
 * prazo até o vencimento passa um pouco dos 10 dias, nunca fica menor.
 */
public final class BillingDayPolicy {

    public static final int MIN_DAY = 1;
    public static final int MAX_DAY = 28;
    public static final int DAYS_BEFORE_DUE = 10;
    /** Uma troca a cada 180 dias: o vencimento não vira ferramenta para empurrar a fatura todo mês. */
    public static final int DAYS_BETWEEN_CHANGES = 180;

    private BillingDayPolicy() {
    }

    public static int closingDayFor(int dueDay) {
        int closing = dueDay - DAYS_BEFORE_DUE;
        if (closing < MIN_DAY) closing += 30; // fecha no mês anterior ao do vencimento
        return Math.min(closing, MAX_DAY);
    }
}
