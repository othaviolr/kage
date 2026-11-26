package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.enums.RefundStatus;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.DocumentMask;
import com.kage.shared.domain.valueobject.ReceiptCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Comprovante de PIX: o que o app mostra e compartilha depois que o dinheiro chegou. Só sai com o PIX
 * concluído — "em processamento" ainda pode falhar, e comprovante de PIX que não aconteceu é a base do
 * golpe do comprovante falso. CPF dos dois lados mascarado.
 */
public class PixReceipts {

    static final String INSTITUTION = "Kage (999)";

    /** @param pixKey só do lado de quem recebeu: a chave usada no envio */
    public record Party(String name, String document, String institution, String branch, String account, String pixKey) {}

    public record Refund(BigDecimal amount, String reason, LocalDateTime refundedAt) {}

    public record Receipt(String type, UUID transactionId, String e2eId, BigDecimal amount, String description,
                          String transactionType, LocalDateTime paidAt, Party payer, Party payee, Refund refund,
                          String authenticationCode) {}

    private final PixTransactionRepository pixTransactionRepository;
    private final PixRefundRepository pixRefundRepository;
    private final AccountHolderDirectory holders;

    public PixReceipts(PixTransactionRepository pixTransactionRepository, PixRefundRepository pixRefundRepository,
                       AccountHolderDirectory holders) {
        this.pixTransactionRepository = pixTransactionRepository;
        this.pixRefundRepository = pixRefundRepository;
        this.holders = holders;
    }

    public Receipt of(UUID transactionId) {
        PixTransaction t = pixTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Transação PIX não encontrada"));
        if (t.getStatus() != PixTransactionStatus.COMPLETED) {
            throw new BusinessRuleException("O comprovante sai quando o PIX é concluído (situação atual: " + t.getStatus() + ")");
        }

        Refund refund = pixRefundRepository.findByOriginalTransactionId(transactionId)
                .filter(r -> r.getRefundStatus() == RefundStatus.COMPLETED)
                .map(r -> new Refund(r.getRefundAmount().amount(), r.getReason(), r.getProcessedAt()))
                .orElse(null);

        return new Receipt("PIX", t.getTransactionId(), t.getE2eId(), t.getAmount().amount(), t.getDescription(),
                t.getTransactionType().name(), t.getCompletedAt(), party(t.getSourceAccountId(), null),
                party(t.getTargetAccountId(), DocumentMask.maskPixKey(t.getTargetPixKey())), refund,
                ReceiptCode.of("PIX", t.getE2eId(), t.getAmount().amount(), t.getCompletedAt(), t.getSourceAccountId(),
                        t.getTargetAccountId()));
    }

    private Party party(UUID accountId, String pixKey) {
        AccountHolderDirectory.Holder holder = holders.holderOf(accountId)
                .orElseThrow(() -> new IllegalStateException("Conta " + accountId + " do PIX não existe mais"));
        return new Party(holder.name(), DocumentMask.mask(holder.document()), INSTITUTION, holder.branch(), holder.account(),
                pixKey);
    }
}
