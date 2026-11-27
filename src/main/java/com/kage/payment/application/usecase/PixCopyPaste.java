package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.repository.PixBillingPaymentRepository;
import com.kage.payment.domain.repository.PixBillingPaymentRepository.Payment;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;
import com.kage.payment.domain.service.AccountValidationService;
import com.kage.payment.domain.service.PixAccountDebit;
import com.kage.payment.domain.service.PixBillingGateway;
import com.kage.payment.domain.service.PixBillingGateway.Billing;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.pix.PixBrCode;
import com.kage.shared.domain.valueobject.DocumentMask;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * PIX copia e cola no PIX da conta Kage: o cliente cola o código, vê quem recebe e o valor, e paga.
 *
 * <ul>
 *   <li><b>Chave de cliente Kage</b> ({@code PIX_KEY}): vira um PIX comum ({@link SendPix}), com o valor
 *       do código (ou o que o pagador digitar, se o código não traz valor), os mesmos limites e o mesmo
 *       comprovante.</li>
 *   <li><b>PIX da fatura do cartão Kage</b> ({@code CARD_INVOICE}): o débito do pagador e o pagamento da
 *       fatura vão na mesma transação, sem esperar a liquidação ({@link PixBillingGateway}). O valor é o
 *       da cobrança, ela vale até expirar e é paga uma vez só. Conta no limite de PIX da conta.</li>
 * </ul>
 *
 * Chave de outro banco ainda não dá para pagar: o Kage só envia PIX entre contas Kage.
 */
public class PixCopyPaste {

    public static final String KIND_PIX_KEY = "PIX_KEY";
    public static final String KIND_CARD_INVOICE = "CARD_INVOICE";

    /**
     * O que o pagador vê antes de confirmar.
     *
     * @param amount         nulo = o código não traz valor: o pagador digita ({@code amountRequired})
     * @param expiresAt      cobrança do Kage: até quando vale; nulo no PIX comum
     */
    public record Preview(String kind, String pixKey, String receiverName, String receiverDocument,
                          String merchantName, String merchantCity, String description, BigDecimal amount,
                          boolean amountRequired, String txid, boolean singleUse, LocalDateTime expiresAt,
                          boolean payable, String notPayableReason) {}

    /**
     * @param transactionId PIX comum: a transação (o comprovante sai quando ela conclui); nulo na cobrança
     * @param paymentId     cobrança do Kage: o pagamento; nulo no PIX comum
     * @param status        PROCESSING (PIX comum, o dinheiro está a caminho) ou COMPLETED (cobrança paga)
     */
    public record Paid(String kind, UUID transactionId, UUID paymentId, String status, BigDecimal amount,
                       String receiverName, String description, LocalDateTime paidAt) {}

    private final PixKeyRepository pixKeys;
    private final AccountHolderDirectory holders;
    private final SendPix sendPix;
    private final PixBillingGateway billing;
    private final PixBillingPaymentRepository billingPayments;
    private final AccountValidationService accountValidation;
    private final PixAccountDebit accountDebit;
    private final PixTransactionRepository transactions;
    private final Clock clock;

    public PixCopyPaste(PixKeyRepository pixKeys, AccountHolderDirectory holders, SendPix sendPix,
                        PixBillingGateway billing, PixBillingPaymentRepository billingPayments,
                        AccountValidationService accountValidation, PixAccountDebit accountDebit,
                        PixTransactionRepository transactions, Clock clock) {
        this.pixKeys = pixKeys;
        this.holders = holders;
        this.sendPix = sendPix;
        this.billing = billing;
        this.billingPayments = billingPayments;
        this.accountValidation = accountValidation;
        this.accountDebit = accountDebit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Preview read(String code) {
        PixBrCode.Parsed parsed = PixBrCode.parse(code);
        if (billing.receives(parsed.pixKey())) {
            Optional<Billing> charge = billing.find(code.strip());
            if (charge.isEmpty()) {
                return preview(parsed, KIND_CARD_INVOICE, null, null, null, parsed.amount(), null, NOT_FOUND_CHARGE);
            }
            Billing b = charge.get();
            return preview(parsed, KIND_CARD_INVOICE, b.beneficiaryName(), null, b.description(), b.amount(),
                    b.expiresAt(), b.notPayableReason());
        }

        Optional<PixKey> key = pixKeys.findByKeyValue(parsed.pixKey());
        String reason = key.isEmpty() ? NOT_FOUND_KEY
                : key.get().getKeyStatus() != PixKeyStatus.ACTIVE ? "Chave PIX destino não está ativa" : null;
        Optional<AccountHolderDirectory.Holder> holder = key.flatMap(k -> holders.holderOf(k.getAccountId()));
        return preview(parsed, KIND_PIX_KEY, holder.map(AccountHolderDirectory.Holder::name).orElse(null),
                holder.map(h -> DocumentMask.mask(h.document())).orElse(null), parsed.info(), parsed.amount(), null, reason);
    }

    /**
     * @param amount só para código sem valor; se o código traz valor, nulo ou o mesmo valor
     * @param description PIX comum: mensagem para quem recebe; nula = a do próprio código
     */
    @Transactional
    public Paid pay(UUID sourceAccountId, String code, BigDecimal amount, String description) {
        PixBrCode.Parsed parsed = PixBrCode.parse(code);
        if (billing.receives(parsed.pixKey())) return payBilling(sourceAccountId, code.strip(), amount);

        BigDecimal value = valueToPay(parsed.amount(), amount);
        SendPix.Output sent = sendPix.execute(new SendPix.Input(sourceAccountId, parsed.pixKey(), Money.of(value),
                description != null && !description.isBlank() ? description : parsed.info()));
        String receiver = holders.holderOf(sent.targetAccountId()).map(AccountHolderDirectory.Holder::name).orElse(null);
        return new Paid(KIND_PIX_KEY, sent.transactionId(), null, sent.status(), sent.amount(), receiver,
                description != null && !description.isBlank() ? description : parsed.info(),
                LocalDateTime.parse(sent.createdAt()));
    }

    /** Pagamento de cobrança do Kage feito pelo copia e cola (quem pagou consulta). */
    public Payment payment(UUID paymentId) {
        return billingPayments.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Pagamento PIX não encontrado"));
    }

    private Paid payBilling(UUID sourceAccountId, String code, BigDecimal amount) {
        Billing charge = billing.find(code).orElseThrow(() -> new NotFoundException(NOT_FOUND_CHARGE));
        if (charge.notPayableReason() != null) throw new BusinessRuleException(charge.notPayableReason());
        if (amount != null && amount.compareTo(charge.amount()) != 0) {
            throw new ValidationException("O valor deste PIX é fixo: " + charge.amount().toPlainString());
        }
        Money value = Money.of(charge.amount());

        // mesma trava e mesmos limites do PIX comum (a soma já inclui as cobranças pagas assim)
        accountValidation.validateBalanceAndLimits(sourceAccountId, value,
                night -> new PixLimitCalculator(transactions, clock).usageOf(sourceAccountId, night));

        UUID paymentId = UUID.randomUUID();
        LocalDateTime now = DomainTime.now();
        accountDebit.debit(sourceAccountId, value, paymentId);
        try {
            billingPayments.insert(new Payment(paymentId, sourceAccountId, charge.chargeId(), KIND_CARD_INVOICE, code,
                    charge.beneficiaryName(), charge.description(), value.amount(), now));
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("PIX já foi pago");
        }
        billing.settle(charge.chargeId());
        return new Paid(KIND_CARD_INVOICE, null, paymentId, "COMPLETED", value.amount(), charge.beneficiaryName(),
                charge.description(), now);
    }

    private static BigDecimal valueToPay(BigDecimal fromCode, BigDecimal typed) {
        if (fromCode != null) {
            if (typed != null && typed.compareTo(fromCode) != 0) {
                throw new ValidationException("O valor deste PIX é fixo: " + fromCode.toPlainString());
            }
            return fromCode;
        }
        if (typed == null || typed.signum() <= 0) {
            throw new ValidationException("Este código não traz valor: informe quanto quer pagar");
        }
        if (typed.scale() > 2 && typed.stripTrailingZeros().scale() > 2) {
            throw new ValidationException("Valor com no máximo 2 casas decimais");
        }
        return typed;
    }

    private static Preview preview(PixBrCode.Parsed parsed, String kind, String receiverName, String receiverDocument,
                                   String description, BigDecimal amount, LocalDateTime expiresAt, String reason) {
        return new Preview(kind, parsed.pixKey(), receiverName, receiverDocument, parsed.merchantName(), parsed.city(),
                description, amount, amount == null, parsed.txid(), parsed.singleUse(), expiresAt, reason == null, reason);
    }

    static final String NOT_FOUND_CHARGE = "Cobrança PIX do Kage não encontrada: gere o código de novo na fatura";
    static final String NOT_FOUND_KEY = "Chave PIX não encontrada no Kage: por enquanto o Kage só envia PIX entre contas Kage";
}
