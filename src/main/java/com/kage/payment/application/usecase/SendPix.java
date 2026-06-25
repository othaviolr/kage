package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;

import java.time.LocalDateTime;
import java.util.UUID;

public class SendPix {

    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final AccountValidationService accountValidationService;

    public SendPix(PixKeyRepository pixKeyRepository, PixTransactionRepository pixTransactionRepository, AccountValidationService accountValidationService) {
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.accountValidationService = accountValidationService;
    }

    public Output execute(Input input) {
        PixKey targetKey = pixKeyRepository.findByKeyValue(input.targetPixKey()).orElseThrow(() -> new DomainException("Chave PIX destino não encontrada"));

        if (!targetKey.getKeyStatus().name().equals("ACTIVE")) {
            throw new DomainException("Chave PIX destino não está ativa");
        }

        if (targetKey.getAccountId().equals(input.sourceAccountId())) {
            throw new DomainException("Não é possível enviar PIX para a própria conta");
        }

        accountValidationService.validateBalanceAndLimits(input.sourceAccountId(), input.amount());

        PixTransaction transaction = PixTransaction.create(input.sourceAccountId(), input.targetPixKey(), input.amount(), input.description(), TransactionType.IMMEDIATE,null);

        transaction.startProcessing(targetKey.getAccountId());

        PixTransaction saved = pixTransactionRepository.save(transaction);

        return new Output(
                saved.getTransactionId(),
                saved.getSourceAccountId(),
                saved.getTargetPixKey(),
                saved.getTargetAccountId(),
                saved.getAmount().amount(),
                saved.getStatus().name(),
                saved.getE2eId(),
                saved.getCreatedAt().toString());
    }

    public record Input(UUID sourceAccountId, String targetPixKey, Money amount, String description) {}

    public record Output(UUID transactionId, UUID sourceAccountId, String targetPixKey, UUID targetAccountId, java.math.BigDecimal amount, String status, String e2eId, String createdAt) {}
}