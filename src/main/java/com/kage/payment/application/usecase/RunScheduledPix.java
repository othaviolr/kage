package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Dispara os PIX agendados até {@code date} (inclusive — um dia que o job não rodou é pego no
 * seguinte). Sem @Transactional: cada agendamento roda na transação do ScheduledPixExecutor, e um
 * que falha não desfaz os outros.
 *
 * Idempotente: só pega PENDING, e o executor confere o status de novo. Se duas instâncias
 * dispararem o mesmo agendamento, o débito continua único: o Account processa cada transactionId
 * uma vez só (Inbox).
 */
public class RunScheduledPix {

    private static final Logger logger = LoggerFactory.getLogger(RunScheduledPix.class);

    public record Output(LocalDate date, int sent, int failed, int skipped) {}

    private final PixTransactionRepository pixTransactionRepository;
    private final ScheduledPixExecutor executor;

    public RunScheduledPix(PixTransactionRepository pixTransactionRepository, ScheduledPixExecutor executor) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.executor = executor;
    }

    public Output execute(LocalDate date) {
        int sent = 0, failed = 0, skipped = 0;
        for (PixTransaction due : pixTransactionRepository.findScheduledDueUntil(date)) {
            UUID id = due.getTransactionId();
            switch (executor.execute(id)) {
                case SENT -> sent++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
            }
        }
        logger.info("PIX agendados de {}: {} enviado(s), {} recusado(s), {} ignorado(s)", date, sent, failed, skipped);
        return new Output(date, sent, failed, skipped);
    }
}
