package com.kage.card.infrastructure.scheduling;

import com.kage.card.application.usecase.RunBillingCycle;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Dispara o ciclo de cobrança uma vez por dia (padrão: 03:00 de Brasília, fora do horário de pico).
 * Registrado só com kage.billing.scheduler-enabled=true (os testes desligam para o job não rodar no
 * meio deles).
 */
public class BillingScheduler {

    private final RunBillingCycle runBillingCycle;
    private final Clock clock;

    public BillingScheduler(RunBillingCycle runBillingCycle, Clock clock) {
        this.runBillingCycle = runBillingCycle;
        this.clock = clock;
    }

    @Scheduled(cron = "${kage.billing.cron:0 0 3 * * *}", zone = "${kage.billing.zone:America/Sao_Paulo}")
    public void runDaily() {
        runBillingCycle.execute(LocalDate.now(clock));
    }
}
