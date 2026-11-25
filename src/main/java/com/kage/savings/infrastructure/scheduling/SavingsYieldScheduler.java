package com.kage.savings.infrastructure.scheduling;

import com.kage.savings.application.usecase.RunSavingsYield;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Rende as caixinhas uma vez por dia (padrão: 01:00 de Brasília, rendendo o dia útil anterior).
 * Registrado só com kage.savings.scheduler-enabled=true (os testes desligam).
 */
public class SavingsYieldScheduler {

    private final RunSavingsYield runSavingsYield;
    private final Clock clock;

    public SavingsYieldScheduler(RunSavingsYield runSavingsYield, Clock clock) {
        this.runSavingsYield = runSavingsYield;
        this.clock = clock;
    }

    @Scheduled(cron = "${kage.savings.cron:0 0 1 * * *}", zone = "${kage.savings.zone:America/Sao_Paulo}")
    public void runDaily() {
        runSavingsYield.execute(LocalDate.now(clock));
    }
}
