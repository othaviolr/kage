package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.shared.domain.exception.DomainException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Executa UM PIX agendado, na própria transação — por isso é um bean separado do laço
 * (RunScheduledPix): @Transactional chamado de dentro da mesma classe não abre transação nova.
 *
 * Mesmo caminho do PIX imediato: lock na conta, limites acumulados do dia, outbox -> débito ->
 * confirmação. Regra de negócio que não passa (sem saldo, chave removida, conta bloqueada) não é
 * exceção: o agendamento vira FAILED com o motivo e o cliente é avisado.
 */
public class ScheduledPixExecutor {

    public enum Result { SENT, FAILED, SKIPPED }

    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final AccountValidationService accountValidationService;
    private final PixEventPublisher pixEventPublisher;
    private final PixRecurrenceChain recurrenceChain;
    private final Clock clock;

    public ScheduledPixExecutor(PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                                AccountValidationService accountValidationService, PixEventPublisher pixEventPublisher,
                                PixRecurrenceChain recurrenceChain, Clock clock) {
        this.recurrenceChain = recurrenceChain;
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.accountValidationService = accountValidationService;
        this.pixEventPublisher = pixEventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Result execute(UUID transactionId) {
        Optional<PixTransaction> found = pixTransactionRepository.findById(transactionId);
        // cancelado ou já executado (outra instância, rodada anterior) entre a busca do lote e agora
        if (found.isEmpty() || found.get().getStatus() != PixTransactionStatus.PENDING) {
            return Result.SKIPPED;
        }
        PixTransaction transaction = found.get();

        try {
            PixKey targetKey = pixKeyRepository.findByKeyValue(transaction.getTargetPixKey())
                    .filter(key -> key.getKeyStatus() == PixKeyStatus.ACTIVE)
                    .orElseThrow(() -> new DomainException("Chave PIX destino não existe mais"));
            if (targetKey.getAccountId().equals(transaction.getSourceAccountId())) {
                throw new DomainException("A chave destino agora é da própria conta");
            }

            accountValidationService.validateBalanceAndLimits(transaction.getSourceAccountId(), transaction.getAmount(),
                    () -> new PixLimitCalculator(pixTransactionRepository, clock).usageOf(transaction.getSourceAccountId()));

            transaction.startProcessing(targetKey.getAccountId());
            PixTransaction saved = pixTransactionRepository.save(transaction);
            pixEventPublisher.publishPixSent(new SendPix.Output(saved.getTransactionId(), saved.getSourceAccountId(),
                    saved.getTargetPixKey(), saved.getTargetAccountId(), saved.getAmount().amount(), saved.getStatus().name(),
                    saved.getE2eId(), saved.getCreatedAt().toString()));
            recurrenceChain.onOccurrenceResolved(saved);
            return Result.SENT;
        } catch (DomainException e) {
            transaction.fail(e.getMessage());
            pixTransactionRepository.save(transaction);
            pixEventPublisher.publishScheduledPixFailed(transaction, e.getMessage());
            recurrenceChain.onOccurrenceResolved(transaction); // sem saldo hoje não cancela as próximas
            return Result.FAILED;
        }
    }
}
