package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRecurrence;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixRecurrenceRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PIX recorrente do lado do cliente: criar, acompanhar e cancelar. Como no PIX agendado, na criação
 * só a chave e o valor são validados; saldo e limites são conferidos no dia de cada ocorrência.
 */
public class RecurringPix {

    public record Input(UUID sourceAccountId, String targetPixKey, Money amount, String description,
                        PixRecurrence.Frequency frequency, LocalDate startDate, int occurrences) {}

    public record Occurrence(int number, LocalDate date, UUID transactionId, String status, String failureReason) {}

    public record View(UUID id, UUID sourceAccountId, String targetPixKey, BigDecimal amount, String description,
                       String frequency, LocalDate startDate, int totalOccurrences, String status,
                       LocalDate nextDate, int remaining, List<Occurrence> occurrences) {}

    private final PixKeyRepository pixKeyRepository;
    private final PixRecurrenceRepository recurrenceRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final PixRecurrenceChain chain;
    private final Clock clock;

    public RecurringPix(PixKeyRepository pixKeyRepository, PixRecurrenceRepository recurrenceRepository,
                        PixTransactionRepository pixTransactionRepository, PixRecurrenceChain chain, Clock clock) {
        this.pixKeyRepository = pixKeyRepository;
        this.recurrenceRepository = recurrenceRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.chain = chain;
        this.clock = clock;
    }

    /** Cria a recorrência e já agenda a primeira ocorrência. */
    @Transactional
    public View create(Input input) {
        SchedulePix.requireValidTarget(pixKeyRepository, input.targetPixKey(), input.sourceAccountId());
        PixRecurrence recurrence = PixRecurrence.create(input.sourceAccountId(), input.targetPixKey(), input.amount(),
                input.description(), input.frequency(), input.startDate(), input.occurrences(),
                LocalDate.now(clock), LocalDateTime.now(clock));
        recurrenceRepository.insert(recurrence);
        chain.advance(recurrence);
        return view(reload(recurrence.getId()));
    }

    public List<View> listActive(UUID accountId) {
        return recurrenceRepository.findActiveByAccount(accountId).stream().map(this::view).toList();
    }

    public View get(UUID id) {
        return view(reload(id));
    }

    /** Encerra a recorrência e cancela a ocorrência que estava agendada. As já enviadas ficam como estão. */
    @Transactional
    public View cancel(UUID id) {
        PixRecurrence recurrence = reload(id);
        recurrence.cancel(LocalDateTime.now(clock));
        recurrenceRepository.update(recurrence);
        for (PixTransaction occurrence : pixTransactionRepository.findByRecurrence(id)) {
            if (occurrence.getStatus() == PixTransactionStatus.PENDING) {
                occurrence.cancel();
                pixTransactionRepository.save(occurrence);
            }
        }
        return view(reload(id));
    }

    private PixRecurrence reload(UUID id) {
        return recurrenceRepository.findById(id).orElseThrow(() -> new NotFoundException("PIX recorrente não encontrado"));
    }

    private View view(PixRecurrence r) {
        List<Occurrence> occurrences = pixTransactionRepository.findByRecurrence(r.getId()).stream()
                .map(t -> new Occurrence(t.getOccurrenceNumber(), t.getScheduledDate().toLocalDate(), t.getTransactionId(),
                        t.getStatus().name(), t.getFailureReason()))
                .toList();
        LocalDate nextDate = occurrences.stream().filter(o -> o.status().equals(PixTransactionStatus.PENDING.name()))
                .map(Occurrence::date).findFirst().orElse(null);
        int resolved = (int) occurrences.stream().filter(o -> !o.status().equals(PixTransactionStatus.PENDING.name())).count();
        int remaining = r.getStatus() == PixRecurrence.Status.ACTIVE ? r.getTotalOccurrences() - resolved : 0;
        return new View(r.getId(), r.getSourceAccountId(), r.getTargetPixKey(), r.getAmount().amount(), r.getDescription(),
                r.getFrequency().name(), r.getStartDate(), r.getTotalOccurrences(), r.getStatus().name(), nextDate,
                remaining, occurrences);
    }
}
