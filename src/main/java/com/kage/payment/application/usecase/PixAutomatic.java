package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Frequency;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Party;
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
                               LocalDate endDate) {}

    public record ChargeView(UUID id, int cycle, LocalDate dueDate, BigDecimal amount, String status,
                             String failureReason, UUID transactionId) {}

    public record View(UUID id, UUID payerAccountId, UUID receiverAccountId, String receiverName, String description,
                       String contractReference, String frequency, BigDecimal fixedAmount, BigDecimal maxAmount,
                       LocalDate startDate, LocalDate endDate, String status, String cancelledBy,
                       List<ChargeView> charges) {}

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
                input.contractReference(), input.frequency(), input.fixedAmount(), input.startDate(), input.endDate(),
                today(), now());
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
        PixAutomaticCharge charge = PixAutomaticCharge.of(authorization, cycle, amount, dueDate, saved.getTransactionId(), now());
        try {
            repository.insertCharge(charge);
        } catch (DuplicateKeyException e) { // corrida: duas cobranças do mesmo ciclo ao mesmo tempo
            throw new BusinessRuleException("Já existe cobrança para o ciclo deste vencimento");
        }
        eventPublisher.publishPixAutomaticChargeScheduled(authorization, charge);
        return chargeView(charge, saved);
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

    /** O pagador cancela uma cobrança até a véspera do vencimento; as próximas seguem. */
    @Transactional
    public ChargeView cancelCharge(UUID chargeId) {
        PixAutomaticCharge charge = repository.findChargeById(chargeId)
                .orElseThrow(() -> new NotFoundException("Cobrança não encontrada"));
        if (!today().isBefore(charge.dueDate())) {
            throw new BusinessRuleException("A cobrança só pode ser cancelada até a véspera do vencimento");
        }
        PixTransaction transaction = pixTransactionRepository.findById(charge.pixTransactionId()).orElseThrow();
        transaction.cancel(); // só PENDING
        return chargeView(charge, pixTransactionRepository.save(transaction));
    }

    // ---------- os dois ----------

    /** Encerra a autorização e cancela as cobranças ainda agendadas. As já pagas ficam como estão. */
    @Transactional
    public View cancel(UUID authorizationId, Party by) {
        PixAutomaticAuthorization authorization = find(authorizationId);
        authorization.cancel(by, now());
        repository.update(authorization);
        for (PixAutomaticCharge charge : repository.findCharges(authorizationId)) {
            pixTransactionRepository.findById(charge.pixTransactionId())
                    .filter(t -> t.getStatus() == PixTransactionStatus.PENDING)
                    .ifPresent(t -> {
                        t.cancel();
                        pixTransactionRepository.save(t);
                    });
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
        List<ChargeView> charges = repository.findCharges(a.getId()).stream()
                .map(c -> chargeView(c, pixTransactionRepository.findById(c.pixTransactionId()).orElseThrow()))
                .toList();
        return new View(a.getId(), a.getPayerAccountId(), a.getReceiverAccountId(), a.getReceiverName(), a.getDescription(),
                a.getContractReference(), a.getFrequency().name(), amount(a.getFixedAmount()), amount(a.getMaxAmount()),
                a.getStartDate(), a.getEndDate(), a.displayStatus(today()).name(),
                a.getCancelledBy() == null ? null : a.getCancelledBy().name(), charges);
    }

    private static ChargeView chargeView(PixAutomaticCharge c, PixTransaction t) {
        String status = switch (t.getStatus()) {
            case PENDING -> "SCHEDULED";
            case PROCESSING -> "PROCESSING";
            case COMPLETED -> "PAID";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
        return new ChargeView(c.id(), c.cycle(), c.dueDate(), c.amount().amount(), status, t.getFailureReason(),
                t.getTransactionId());
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
