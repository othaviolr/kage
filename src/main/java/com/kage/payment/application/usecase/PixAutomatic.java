package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Frequency;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Party;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.RetryPolicy;
import com.kage.payment.domain.entity.PixAutomaticCharge;
import com.kage.payment.domain.entity.PixKey;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixKeyStatus;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixAutomaticRepository;
import com.kage.payment.domain.repository.PixKeyRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.payment.domain.service.AccountHolderDirectory;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * PIX Automático, os dois lados:
 * - quem recebe pede a autorização (identifica o pagador pela chave PIX dele), manda uma cobrança
 *   por ciclo e pode cancelar;
 * - o pagador autoriza (com teto opcional no valor variável) ou recusa, cancela uma cobrança até a
 *   véspera e a autorização quando quiser.
 *
 * A cobrança aceita vira um PIX agendado comum para o vencimento: saldo e limites são conferidos no
 * dia pelo ScheduledPixExecutor, e sem saldo o pagador é avisado como em qualquer agendamento.
 */
public class PixAutomatic {

    public record RequestInput(UUID receiverAccountId, String receiverPixKey, String payerPixKey, String description,
                               String contractReference, Frequency frequency, Money fixedAmount, LocalDate startDate,
                               LocalDate endDate, RetryPolicy retryPolicy) {}

    public record AttemptView(int number, LocalDate date, String status, String failureReason, UUID transactionId) {}

    /** Situação, motivo e transação são os da tentativa mais recente. */
    public record ChargeView(UUID id, int cycle, LocalDate dueDate, BigDecimal amount, String status,
                             String failureReason, UUID transactionId, List<AttemptView> attempts) {}

    public record View(UUID id, UUID payerAccountId, UUID receiverAccountId, String receiverName, String description,
                       String contractReference, String frequency, BigDecimal fixedAmount, BigDecimal maxAmount,
                       String retryPolicy, LocalDate startDate, LocalDate endDate, String status, String cancelledBy,
                       List<ChargeView> charges) {}

    /** Só falta de dinheiro (saldo ou limite) justifica tentar de novo; chave removida, por exemplo, não. */
    private static boolean retryable(PixTransaction attempt) {
        String reason = attempt.getFailureReason();
        return attempt.getStatus() == PixTransactionStatus.FAILED && reason != null
                && (reason.contains("Saldo insuficiente") || reason.contains("limite"));
    }

    private final PixAutomaticRepository repository;
    private final PixKeyRepository pixKeyRepository;
    private final PixTransactionRepository pixTransactionRepository;
    private final AccountHolderDirectory holders;
    private final PixEventPublisher eventPublisher;
    private final Clock clock;

    public PixAutomatic(PixAutomaticRepository repository, PixKeyRepository pixKeyRepository,
                        PixTransactionRepository pixTransactionRepository, AccountHolderDirectory holders,
                        PixEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.pixKeyRepository = pixKeyRepository;
        this.pixTransactionRepository = pixTransactionRepository;
        this.holders = holders;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ---------- quem recebe ----------

    @Transactional
    public View request(RequestInput input) {
        PixKey receiverKey = activeKey(input.receiverPixKey(), "Chave PIX de recebimento não encontrada");
        if (!receiverKey.getAccountId().equals(input.receiverAccountId())) {
            throw new BusinessRuleException("A chave de recebimento não é desta conta");
        }
        PixKey payerKey = activeKey(input.payerPixKey(), "Chave PIX do pagador não encontrada");
        AccountHolderDirectory.Holder receiver = activeHolder(input.receiverAccountId(), "Conta recebedora");
        activeHolder(payerKey.getAccountId(), "Conta do pagador");

        PixAutomaticAuthorization authorization = PixAutomaticAuthorization.request(payerKey.getAccountId(),
                input.receiverAccountId(), input.receiverPixKey(), receiver.name(), input.description(),
                input.contractReference(), input.frequency(), input.fixedAmount(), input.retryPolicy(), input.startDate(),
                input.endDate(), today(), now());
        repository.insert(authorization);
        eventPublisher.publishPixAutomaticRequested(authorization);
        return view(authorization);
    }

    /** Cobrança de um ciclo: confere as regras e agenda o PIX para o vencimento. */
    @Transactional
    public ChargeView submitCharge(UUID authorizationId, Money amount, LocalDate dueDate) {
        PixAutomaticAuthorization authorization = find(authorizationId);
        int cycle = authorization.cycleForCharge(amount, dueDate, today());
        if (repository.findCharges(authorizationId).stream().anyMatch(c -> c.cycle() == cycle)) {
            throw new BusinessRuleException("Já existe cobrança para o ciclo deste vencimento");
        }

        PixTransaction scheduled = PixTransaction.schedule(authorization.getPayerAccountId(),
                authorization.getReceiverPixKey(), amount, "PIX Automático: " + authorization.getDescription(),
                dueDate, today());
        PixTransaction saved = pixTransactionRepository.save(scheduled);
        PixAutomaticCharge charge = PixAutomaticCharge.of(authorization, cycle, amount, dueDate, now());
        try {
            repository.insertCharge(charge);
        } catch (DuplicateKeyException e) { // corrida: duas cobranças do mesmo ciclo ao mesmo tempo
            throw new BusinessRuleException("Já existe cobrança para o ciclo deste vencimento");
        }
        repository.insertAttempt(PixAutomaticCharge.Attempt.of(charge, 1, dueDate, saved.getTransactionId(), now()));
        eventPublisher.publishPixAutomaticChargeScheduled(authorization, charge, 1, dueDate);
        return chargeView(charge);
    }

    /**
     * Nova tentativa de uma cobrança recusada por falta de saldo ou de limite: só se a autorização
     * previr, no máximo 3, uma por dia, até 7 dias depois do vencimento. Vira outro PIX agendado, e o
     * pagador é avisado como na cobrança original (pode cancelar até a véspera).
     */
    @Transactional
    public ChargeView retryCharge(UUID chargeId, LocalDate date) {
        PixAutomaticCharge charge = findCharge(chargeId);
        PixAutomaticAuthorization authorization = find(charge.authorizationId());
        List<PixAutomaticCharge.Attempt> attempts = repository.findAttempts(chargeId);
        PixAutomaticCharge.Attempt last = attempts.get(attempts.size() - 1);
        if (!retryable(transactionOf(last))) {
            throw new BusinessRuleException("Só dá para tentar de novo uma cobrança recusada por falta de saldo ou de limite");
        }
        authorization.checkRetry(charge.dueDate(), last.scheduledDate(), attempts.size(), date, today());

        int number = attempts.size() + 1;
        PixTransaction scheduled = pixTransactionRepository.save(PixTransaction.schedule(authorization.getPayerAccountId(),
                authorization.getReceiverPixKey(), charge.amount(), "PIX Automático: " + authorization.getDescription()
                        + " (nova tentativa " + (number - 1) + ")", date, today()));
        try {
            repository.insertAttempt(PixAutomaticCharge.Attempt.of(charge, number, date, scheduled.getTransactionId(), now()));
        } catch (DuplicateKeyException e) { // corrida: duas novas tentativas ao mesmo tempo
            throw new BusinessRuleException("Já existe uma tentativa agendada para esta cobrança");
        }
        eventPublisher.publishPixAutomaticChargeScheduled(authorization, charge, number, date);
        return chargeView(charge);
    }

    // ---------- pagador ----------

    @Transactional
    public View approve(UUID authorizationId, Money maxAmount) {
        PixAutomaticAuthorization authorization = find(authorizationId);
        authorization.approve(maxAmount, today(), now());
        repository.update(authorization);
        return get(authorizationId);
    }

    @Transactional
    public View reject(UUID authorizationId) {
        PixAutomaticAuthorization authorization = find(authorizationId);
        authorization.reject(today(), now());
        repository.update(authorization);
        return get(authorizationId);
    }

    /**
     * O pagador cancela a tentativa agendada (a original ou uma nova) até a véspera dela; cancelada,
     * a cobrança não tem mais novas tentativas. As próximas cobranças seguem.
     */
    @Transactional
    public ChargeView cancelCharge(UUID chargeId) {
        PixAutomaticCharge charge = findCharge(chargeId);
        List<PixAutomaticCharge.Attempt> attempts = repository.findAttempts(chargeId);
        PixAutomaticCharge.Attempt last = attempts.get(attempts.size() - 1);
        if (!today().isBefore(last.scheduledDate())) {
            throw new BusinessRuleException("A cobrança só pode ser cancelada até a véspera do pagamento agendado");
        }
        PixTransaction transaction = transactionOf(last);
        transaction.cancel(); // só PENDING
        pixTransactionRepository.save(transaction);
        return chargeView(charge);
    }

    // ---------- os dois ----------

    /** Encerra a autorização e cancela as cobranças ainda agendadas. As já pagas ficam como estão. */
    @Transactional
    public View cancel(UUID authorizationId, Party by) {
        PixAutomaticAuthorization authorization = find(authorizationId);
        authorization.cancel(by, now());
        repository.update(authorization);
        for (PixAutomaticCharge charge : repository.findCharges(authorizationId)) {
            for (PixAutomaticCharge.Attempt attempt : repository.findAttempts(charge.id())) {
                PixTransaction t = transactionOf(attempt);
                if (t.getStatus() == PixTransactionStatus.PENDING) {
                    t.cancel();
                    pixTransactionRepository.save(t);
                }
            }
        }
        return get(authorizationId);
    }

    public List<View> listByAccount(UUID accountId) {
        return repository.findByAccount(accountId).stream().map(this::view).toList();
    }

    public View get(UUID authorizationId) {
        return view(find(authorizationId));
    }

    private View view(PixAutomaticAuthorization a) {
        List<ChargeView> charges = repository.findCharges(a.getId()).stream().map(this::chargeView).toList();
        return new View(a.getId(), a.getPayerAccountId(), a.getReceiverAccountId(), a.getReceiverName(), a.getDescription(),
                a.getContractReference(), a.getFrequency().name(), amount(a.getFixedAmount()), amount(a.getMaxAmount()),
                a.getRetryPolicy().name(), a.getStartDate(), a.getEndDate(), a.displayStatus(today()).name(),
                a.getCancelledBy() == null ? null : a.getCancelledBy().name(), charges);
    }

    private ChargeView chargeView(PixAutomaticCharge c) {
        List<AttemptView> attempts = repository.findAttempts(c.id()).stream().map(attempt -> {
            PixTransaction t = transactionOf(attempt);
            return new AttemptView(attempt.number(), attempt.scheduledDate(), statusOf(t), t.getFailureReason(),
                    t.getTransactionId());
        }).toList();
        AttemptView last = attempts.get(attempts.size() - 1);
        return new ChargeView(c.id(), c.cycle(), c.dueDate(), c.amount().amount(), last.status(), last.failureReason(),
                last.transactionId(), attempts);
    }

    private static String statusOf(PixTransaction t) {
        return switch (t.getStatus()) {
            case PENDING -> "SCHEDULED";
            case PROCESSING -> "PROCESSING";
            case COMPLETED -> "PAID";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private PixTransaction transactionOf(PixAutomaticCharge.Attempt attempt) {
        return pixTransactionRepository.findById(attempt.pixTransactionId()).orElseThrow();
    }

    private PixAutomaticCharge findCharge(UUID chargeId) {
        return repository.findChargeById(chargeId).orElseThrow(() -> new NotFoundException("Cobrança não encontrada"));
    }

    private PixKey activeKey(String keyValue, String notFound) {
        return pixKeyRepository.findByKeyValue(keyValue)
                .filter(key -> key.getKeyStatus() == PixKeyStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(notFound));
    }

    private AccountHolderDirectory.Holder activeHolder(UUID accountId, String label) {
        AccountHolderDirectory.Holder holder = holders.holderOf(accountId)
                .orElseThrow(() -> new NotFoundException(label + " não encontrada"));
        if (!holder.active()) throw new BusinessRuleException(label + " não está ativa");
        return holder;
    }

    private PixAutomaticAuthorization find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Autorização de PIX Automático não encontrada"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static BigDecimal amount(Money value) {
        return value == null ? null : value.amount();
    }
}
