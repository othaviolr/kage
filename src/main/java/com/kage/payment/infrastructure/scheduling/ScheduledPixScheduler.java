package com.kage.payment.infrastructure.scheduling;

import com.kage.payment.application.usecase.RunScheduledPix;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Dispara os PIX agendados do dia às 06:00 de Brasília: logo depois do fim da janela noturna
 * (20h–6h), para o limite noturno não barrar um agendamento feito de dia.
 * Registrado só com kage.pix.scheduler-enabled=true (os testes desligam).
 */
public class ScheduledPixScheduler {

    private final RunScheduledPix runScheduledPix;
    private final Clock clock;

    public ScheduledPixScheduler(RunScheduledPix runScheduledPix, Clock clock) {
        this.runScheduledPix = runScheduledPix;
        this.clock = clock;
    }

    @Scheduled(cron = "${kage.pix.scheduled-cron:0 0 6 * * *}", zone = "${kage.pix.zone:America/Sao_Paulo}")
    public void runDaily() {
        runScheduledPix.execute(LocalDate.now(clock));
    }
}
