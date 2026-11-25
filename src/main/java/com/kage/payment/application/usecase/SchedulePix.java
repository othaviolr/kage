package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Agenda um PIX para uma data futura. Na hora só valida chave e valor; saldo e limites são
 * conferidos no dia (ScheduledPixExecutor). A chave é lida de novo na execução: se o destinatário
 * a remover até lá, o PIX falha em vez de ir para uma conta que talvez não seja mais a certa.
 */
public class SchedulePix {

    public record Input(UUID sourceAccountId, String targetPixKey, Money amount, String description, LocalDate scheduledDate) {}
    public record Output(UUID transactionId, UUID sourceAccountId, String targetPixKey, BigDecimal amount,
                         String description, LocalDate scheduledDate, String status,
                         UUID recurrenceId, Integer occurrenceNumber) {}

    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final Clock clock;

    public SchedulePix(PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository, Clock clock) {
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.clock = clock;
    }

    public Output execute(Input input) {
        requireValidTarget(pixKeyRepository, input.targetPixKey(), input.sourceAccountId());

        PixTransaction scheduled = PixTransaction.schedule(input.sourceAccountId(), input.targetPixKey(), input.amount(),
                input.description(), input.scheduledDate(), LocalDate.now(clock));
        return toOutput(pixTransactionRepository.save(scheduled));
    }

    /** Chave existe, está ativa e não é da própria conta (vale para agendado e recorrente). */
    static void requireValidTarget(PixKeyRepository pixKeyRepository, String targetPixKey, UUID sourceAccountId) {
        PixKey targetKey = pixKeyRepository.findByKeyValue(targetPixKey)
                .orElseThrow(() -> new NotFoundException("Chave PIX destino não encontrada"));
        if (targetKey.getKeyStatus() != PixKeyStatus.ACTIVE) {
            throw new BusinessRuleException("Chave PIX destino não está ativa");
        }
        if (targetKey.getAccountId().equals(sourceAccountId)) {
            throw new BusinessRuleException("Não é possível enviar PIX para a própria conta");
        }
    }

    static Output toOutput(PixTransaction t) {
        return new Output(t.getTransactionId(), t.getSourceAccountId(), t.getTargetPixKey(), t.getAmount().amount(),
                t.getDescription(), t.getScheduledDate().toLocalDate(), t.getStatus().name(), t.getRecurrenceId(),
                t.getOccurrenceNumber());
    }
}
