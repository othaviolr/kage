package com.kage.notification.infrastructure.scheduling;

import com.kage.notification.application.usecase.QuietHoursSummaries;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * A cada 5 minutos: manda o resumo de quem acabou de sair do horário silencioso. Idempotente (o
 * resumo de uma janela sai uma vez só), então rodar em duas instâncias não duplica.
 * Registrado só com kage.notification.quiet-summary.scheduler-enabled=true (os testes desligam).
 */
public class QuietHoursSummaryScheduler {

    private final QuietHoursSummaries summaries;
    private final Clock clock;

    public QuietHoursSummaryScheduler(QuietHoursSummaries summaries, Clock clock) {
        this.summaries = summaries;
        this.clock = clock;
    }

    @Scheduled(cron = "${kage.notification.quiet-summary.cron:0 */5 * * * *}",
            zone = "${kage.notification.quiet-summary.zone:America/Sao_Paulo}")
    public void run() {
        summaries.run(LocalDateTime.now(clock));
    }
}
