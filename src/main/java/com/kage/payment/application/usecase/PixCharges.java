package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixChargeRepository;
import com.kage.payment.domain.repository.PixChargeRepository.Charge;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.pix.PixBrCode;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Cobrar com PIX copia e cola: o cliente escolhe uma chave sua, o valor (ou deixa em aberto, para quem
 * paga escolher) e uma mensagem; o Kage monta o código (BR Code) com um txid que identifica a cobrança.
 *
 * Quem paga pelo PIX da conta Kage ({@link PixCopyPaste}) fica registrado na cobrança, e quem cobrou vê
 * quem pagou, quanto e se o PIX já concluiu. A cobrança de <b>uso único</b> recebe um pagamento só
 * (padrão quando tem valor); a sem uso único serve para vários pagadores (uma vaquinha, por exemplo).
 * Pago de outro banco, o PIX chega como um PIX qualquer: o Kage não recebe PIX de fora.
 *
 * A cobrança pode ter <b>validade</b> (de 1 a 365 dias, até o fim do dia): vencida, o Kage não paga mais o
 * código. Quando um PIX que pagou a cobrança conclui, quem cobrou é avisado ({@link #paymentCompleted}).
 */
public class PixCharges {

    static final int LIST_LIMIT = 50;
    static final int MAX_MESSAGE = 72;
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    static final int MAX_VALID_DAYS = 365;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DEFAULT_CITY = "SAO PAULO";

    /**
     * @param status    OPEN, PAID (uso único com o PIX concluído), EXPIRED (venceu sem ser paga) ou CANCELLED
     * @param expiresAt nulo = sem validade
     */
    public record ChargeView(UUID id, UUID accountId, String pixKey, BigDecimal amount, boolean amountOpen,
                             String message, boolean singleUse, String txid, String pixCopyPaste, String status,
                             LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime cancelledAt, int paymentCount,
                             BigDecimal received, List<PaymentView> payments) {}

    /** @param status PROCESSING (a caminho), COMPLETED ou FAILED (o dinheiro não saiu de quem pagou) */
    public record PaymentView(UUID transactionId, String payerName, BigDecimal amount, String status,
                              LocalDateTime paidAt) {}

    private final PixChargeRepository charges;
    private final PixKeyRepository pixKeys;
    private final PixTransactionRepository transactions;
    private final AccountHolderDirectory holders;
    private final PixEventPublisher events;

    public PixCharges(PixChargeRepository charges, PixKeyRepository pixKeys, PixTransactionRepository transactions,
                      AccountHolderDirectory holders, PixEventPublisher events) {
        this.events = events;
        this.charges = charges;
        this.pixKeys = pixKeys;
        this.transactions = transactions;
        this.holders = holders;
    }

    /**
     * @param amount    nulo = quem paga escolhe o valor
     * @param singleUse    nulo = uso único quando tem valor, aberto quando não tem
     * @param validForDays nulo = sem validade; senão vale até o fim do dia, daqui a tantos dias (1 a 365)
     */
    @Transactional
    public ChargeView create(UUID accountId, String pixKey, BigDecimal amount, String message, Boolean singleUse,
                             Integer validForDays) {
        if (validForDays != null && (validForDays < 1 || validForDays > MAX_VALID_DAYS)) {
            throw new ValidationException("Validade de 1 a " + MAX_VALID_DAYS + " dias");
        }
        if (pixKey == null || pixKey.isBlank()) throw new ValidationException("Escolha a chave PIX que vai receber");
        PixKey key = pixKeys.findByKeyValue(pixKey.strip())
                .filter(k -> k.getAccountId().equals(accountId))
                .orElseThrow(() -> new NotFoundException("Chave PIX não encontrada nesta conta"));
        if (key.getKeyStatus() != PixKeyStatus.ACTIVE) throw new BusinessRuleException("A chave PIX não está ativa");
        AccountHolderDirectory.Holder holder = holders.holderOf(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));
        if (!holder.active()) throw new BusinessRuleException("A conta não está ativa");

        BigDecimal value = amount == null ? null : validAmount(amount);
        String text = message == null || message.isBlank() ? null : message.strip();
        int room = Math.min(MAX_MESSAGE, PixBrCode.maxInfoLength(key.getKeyValue()));
        if (text != null && text.length() > room) {
            throw new ValidationException("Mensagem com até " + room + " caracteres para esta chave");
        }
        boolean once = singleUse == null ? value != null : singleUse;

        UUID id = UUID.randomUUID();
        String txid = "KG" + id.toString().replace("-", "").substring(0, 23).toUpperCase(Locale.ROOT);
        String code = PixBrCode.of(key.getKeyValue(), value, holder.name(),
                holder.city() == null ? DEFAULT_CITY : holder.city(), txid, once, text);
        LocalDateTime now = DomainTime.now();
        LocalDateTime expiresAt = validForDays == null ? null
                : now.toLocalDate().plusDays(validForDays).atTime(LocalTime.of(23, 59, 59));
        Charge charge = new Charge(id, accountId, key.getKeyValue(), txid, value, text, once, code, "OPEN",
                now, null, expiresAt);
        charges.insert(charge);
        return view(charge);
    }

    public List<ChargeView> list(UUID accountId) {
        return charges.findByAccountId(accountId, LIST_LIMIT).stream().map(this::view).toList();
    }

    public ChargeView get(UUID chargeId) {
        return view(find(chargeId));
    }

    /** Quem cobrou desiste: o código deixa de ser pago pelo Kage. */
    @Transactional
    public ChargeView cancel(UUID chargeId) {
        Charge charge = charges.findByIdForUpdate(chargeId)
                .orElseThrow(() -> new NotFoundException("Cobrança PIX não encontrada"));
        if ("CANCELLED".equals(charge.status())) throw new BusinessRuleException("A cobrança já foi cancelada");
        if (charge.singleUse() && taken(charge)) throw new BusinessRuleException("A cobrança já foi paga");
        charges.cancel(chargeId, DomainTime.now());
        return view(find(chargeId));
    }

    public UUID accountOf(UUID chargeId) {
        return find(chargeId).accountId();
    }

    // ---- lado de quem paga (PixCopyPaste) ----

    /** A cobrança Kage desse código, se o txid for de uma e a chave bater. */
    Optional<Charge> forCode(PixBrCode.Parsed parsed) {
        if (parsed.txid() == null) return Optional.empty();
        return charges.findByTxid(parsed.txid()).filter(c -> c.pixKey().equals(parsed.pixKey()));
    }

    /** Nulo = pode pagar. */
    String notPayableReason(Charge charge, String code) {
        if (!charge.code().equals(code)) return "Código alterado: peça um código novo a quem está cobrando";
        if ("CANCELLED".equals(charge.status())) return "Cobrança cancelada por quem está cobrando";
        if (charge.singleUse() && taken(charge)) return "Cobrança já foi paga";
        if (expired(charge, DomainTime.now())) return "Cobrança venceu em " + charge.expiresAt().format(DATE);
        return null;
    }

    Charge lock(UUID chargeId) {
        return charges.findByIdForUpdate(chargeId).orElseThrow(() -> new NotFoundException("Cobrança PIX não encontrada"));
    }

    void recordPayment(Charge charge, UUID transactionId, UUID payerAccountId, BigDecimal amount, LocalDateTime at) {
        charges.insertPayment(new PixChargeRepository.Payment(transactionId, charge.id(), payerAccountId, amount, at));
    }

    /**
     * Um PIX concluiu: se ele pagou uma cobrança, avisa quem cobrou (quem pagou, quanto e qual cobrança).
     * Roda na transação da conclusão do PIX, então o aviso sai uma vez só.
     */
    public void paymentCompleted(UUID transactionId) {
        charges.findPaymentByTransactionId(transactionId).ifPresent(payment -> {
            Charge charge = find(payment.chargeId());
            String payer = holders.holderOf(payment.payerAccountId()).map(AccountHolderDirectory.Holder::name).orElse(null);
            events.publishChargePaid(new ChargePaid(charge.id(), charge.accountId(), transactionId, payer,
                    payment.amount(), charge.message(), charge.singleUse()));
        });
    }

    /** O aviso para quem cobrou. @param message a mensagem da cobrança (nula se não tinha) */
    public record ChargePaid(UUID chargeId, UUID receiverAccountId, UUID transactionId, String payerName,
                             BigDecimal amount, String message, boolean singleUse) {}

    // ----

    private static boolean expired(Charge charge, LocalDateTime now) {
        return charge.expiresAt() != null && now.isAfter(charge.expiresAt());
    }

    /** Uso único: um PIX a caminho ou concluído já ocupa a cobrança; o que falhou libera. */
    private boolean taken(Charge charge) {
        return charges.paymentsOf(charge.id()).stream()
                .map(p -> status(p.transactionId()))
                .anyMatch(s -> s == PixTransactionStatus.PROCESSING || s == PixTransactionStatus.COMPLETED);
    }

    private PixTransactionStatus status(UUID transactionId) {
        return transactions.findById(transactionId).map(PixTransaction::getStatus).orElse(PixTransactionStatus.FAILED);
    }

    private ChargeView view(Charge c) {
        List<PaymentView> payments = charges.paymentsOf(c.id()).stream().map(p -> new PaymentView(p.transactionId(),
                holders.holderOf(p.payerAccountId()).map(AccountHolderDirectory.Holder::name).orElse(null), p.amount(),
                status(p.transactionId()).name(), p.paidAt())).toList();
        BigDecimal received = payments.stream().filter(p -> p.status().equals("COMPLETED"))
                .map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean paid = c.singleUse() && payments.stream().anyMatch(p -> p.status().equals("COMPLETED"));
        String status = "CANCELLED".equals(c.status()) ? "CANCELLED" : paid ? "PAID"
                : expired(c, DomainTime.now()) ? "EXPIRED" : "OPEN";
        return new ChargeView(c.id(), c.accountId(), c.pixKey(), c.amount(), c.amount() == null, c.message(),
                c.singleUse(), c.txid(), c.code(), status, c.createdAt(), c.expiresAt(), c.cancelledAt(), payments.size(),
                received.setScale(2, RoundingMode.UNNECESSARY), payments);
    }

    private Charge find(UUID chargeId) {
        return charges.findById(chargeId).orElseThrow(() -> new NotFoundException("Cobrança PIX não encontrada"));
    }

    private static BigDecimal validAmount(BigDecimal amount) {
        if (amount.signum() <= 0) throw new ValidationException("Valor deve ser maior que zero");
        if (amount.stripTrailingZeros().scale() > 2) throw new ValidationException("Valor com no máximo 2 casas decimais");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new ValidationException("Valor de até R$ 1.000.000,00");
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
