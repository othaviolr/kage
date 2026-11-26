package com.kage.account.infrastructure.scheduling;

import com.kage.account.application.usecase.LimitChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.UUID;

/**
 * Coloca em vigor os aumentos de limite que completaram 24h. O PIX e o saque já aplicam na hora de
 * usar; o job cobre o resto (consulta da conta). Cada conta na própria transação: uma que falha
 * (corrida com outra operação) não segura as outras e é pega na rodada seguinte.
 * Registrado só com kage.limits.scheduler-enabled=true (os testes desligam).
 */
public class LimitChangesScheduler {

    private static final Logger logger = LoggerFactory.getLogger(LimitChangesScheduler.class);
    private static final int BATCH = 500;

    private final LimitChanges limitChanges;

    public LimitChangesScheduler(LimitChanges limitChanges) {
        this.limitChanges = limitChanges;
    }

    @Scheduled(cron = "${kage.limits.cron:0 */10 * * * *}", zone = "${kage.limits.zone:America/Sao_Paulo}")
    public void applyDue() {
        List<UUID> accounts = limitChanges.accountsWithDue(BATCH);
        for (UUID accountId : accounts) {
            try {
                limitChanges.applyDue(accountId);
            } catch (RuntimeException e) {
                logger.warn("Aumento de limite da conta {} fica para a próxima rodada: {}", accountId, e.getMessage());
            }
        }
        if (!accounts.isEmpty()) logger.info("Aumentos de limite aplicados em {} conta(s)", accounts.size());
    }
}
