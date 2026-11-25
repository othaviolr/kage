package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.domain.service.PixLimitUsage;
import com.kage.payment.domain.service.PixNightWindow;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

public class SendPix {

    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final AccountValidationService accountValidationService;
    private final PixEventPublisher pixEventPublisher;
    private final Clock clock;

    public SendPix(PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository,
                   AccountValidationService accountValidationService, PixEventPublisher pixEventPublisher,
                   Clock clock) {
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.accountValidationService = accountValidationService;
        this.pixEventPublisher = pixEventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Output execute(Input input) {
        PixKey targetKey = pixKeyRepository.findByKeyValue(input.targetPixKey()).orElseThrow(() -> new NotFoundException("Chave PIX destino não encontrada"));

        if (targetKey.getKeyStatus() != PixKeyStatus.ACTIVE) {
            throw new BusinessRuleException("Chave PIX destino não está ativa");
        }

        if (targetKey.getAccountId().equals(input.sourceAccountId())) {
            throw new BusinessRuleException("Não é possível enviar PIX para a própria conta");
        }

        accountValidationService.validateBalanceAndLimits(input.sourceAccountId(), input.amount(),
                () -> limitUsage(input.sourceAccountId()));

        PixTransaction transaction = PixTransaction.create(input.sourceAccountId(), input.targetPixKey(), input.amount(), input.description(), TransactionType.IMMEDIATE, null);

        transaction.startProcessing(targetKey.getAccountId());

        PixTransaction saved = pixTransactionRepository.save(transaction);

        Output output = new Output(
                saved.getTransactionId(),
                saved.getSourceAccountId(),
                saved.getTargetPixKey(),
                saved.getTargetAccountId(),
                saved.getAmount().amount(),
                saved.getStatus().name(),
                saved.getE2eId(),
                saved.getCreatedAt().toString());

        pixEventPublisher.publishPixSent(output);

        return output;
    }

    private PixLimitUsage limitUsage(UUID sourceAccountId) {
        LocalDateTime now = LocalDateTime.now(clock);
        Money sentToday = pixTransactionRepository.sumAmountSentSince(sourceAccountId, now.toLocalDate().atStartOfDay());

        boolean night = PixNightWindow.isNight(now);
        Money sentTonight = night
                ? pixTransactionRepository.sumAmountSentSince(sourceAccountId, PixNightWindow.startOfCurrentNight(now))
                : Money.ZERO;

        return new PixLimitUsage(sentToday, sentTonight, night);
    }

    public record Input(UUID sourceAccountId, String targetPixKey, Money amount, String description) {}

    public record Output(UUID transactionId, UUID sourceAccountId, String targetPixKey, UUID targetAccountId, java.math.BigDecimal amount, String status, String e2eId, String createdAt) {}
}