package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRecurrence;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.repository.PixRecurrenceRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Faz o PIX recorrente andar: quando uma ocorrência é resolvida (enviada, recusada no dia ou pulada
 * pelo cliente), agenda a seguinte. Roda na transação de quem chamou: se a ocorrência não for salva,
 * a próxima também não é criada. O locking otimista da recorrência e o índice único
 * (recorrência, número) impedem que duas execuções criem a mesma ocorrência.
 */
public class PixRecurrenceChain {

    private final PixRecurrenceRepository recurrenceRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final Clock clock;

    public PixRecurrenceChain(PixRecurrenceRepository recurrenceRepository,
                              PixTransactionRepository pixTransactionRepository, Clock clock) {
        this.recurrenceRepository = recurrenceRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.clock = clock;
    }

    /** Agenda a ocorrência seguinte, se houver. Devolve a ocorrência criada. */
    public Optional<PixTransaction> advance(PixRecurrence recurrence) {
        OptionalInt next = recurrence.advance(LocalDateTime.now(clock));
        recurrenceRepository.update(recurrence);
        if (next.isEmpty()) return Optional.empty();
        return Optional.of(pixTransactionRepository.save(PixTransaction.occurrenceOf(recurrence, next.getAsInt())));
    }

    /** Chamado depois que uma ocorrência saiu de PENDING. PIX que não é de recorrência é ignorado. */
    public void onOccurrenceResolved(PixTransaction occurrence) {
        if (!occurrence.isRecurring()) return;
        recurrenceRepository.findById(occurrence.getRecurrenceId())
                .filter(r -> r.getOccurrencesCreated() == occurrence.getOccurrenceNumber()) // só a mais recente anda
                .ifPresent(this::advance);
    }
}
