package com.kage.account.application.usecase;

import com.kage.account.domain.entity.Account;
import com.kage.account.domain.enums.LimitType;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.domain.repository.LimitChangeRepository;
import com.kage.account.domain.repository.LimitChangeRepository.LimitChangeRequest;
import com.kage.account.domain.repository.LimitChangeRepository.Status;
import com.kage.account.domain.service.LimitPolicy;
import com.kage.account.domain.valueobject.Limits;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Limites da conta: consultar, alterar e cancelar um aumento pendente. A regra está na LimitPolicy
 * (diminuir vale já; aumentar, 24h depois). Os aumentos vencidos entram em vigor:
 * - na hora de usar o limite (PIX e saque chamam {@link #applyDue(Account, LocalDateTime)} com a conta
 *   travada), para valer no minuto certo;
 * - na consulta dos limites;
 * - e pelo job periódico, para o resto (GET da conta, extrato).
 */
public class LimitChanges {

    public record Input(UUID accountId, BigDecimal pixDailyLimit, BigDecimal pixNightLimit,
                        BigDecimal dailyWithdrawalLimit, BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit) {}

    public record PendingView(UUID id, String type, BigDecimal currentAmount, BigDecimal requestedAmount,
                              LocalDateTime requestedAt, LocalDateTime effectiveAt) {}

    public record View(UUID accountId, BigDecimal pixDailyLimit, BigDecimal pixNightLimit, BigDecimal dailyWithdrawalLimit,
                       BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit, List<PendingView> pending) {}

    private final AccountRepository accountRepository;
    private final LimitChangeRepository repository;
    private final AccountEventPublisher eventPublisher;
    private final Clock clock;

    public LimitChanges(AccountRepository accountRepository, LimitChangeRepository repository,
                        AccountEventPublisher eventPublisher, Clock clock) {
        this.accountRepository = accountRepository;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public View view(UUID accountId) {
        Account account = find(accountId);
        if (applyDue(account, now())) accountRepository.save(account);
        return view(account);
    }

    /** Campos nulos ficam como estão. Reduções valem já; aumentos viram pedido para daqui a 24h. */
    @Transactional
    public View change(Input input) {
        LocalDateTime now = now();
        Account account = find(input.accountId());
        applyDue(account, now);

        Map<LimitType, Money> requested = new EnumMap<>(LimitType.class);
        put(requested, LimitType.PIX_DAILY, input.pixDailyLimit());
        put(requested, LimitType.PIX_NIGHT, input.pixNightLimit());
        put(requested, LimitType.DAILY_WITHDRAWAL, input.dailyWithdrawalLimit());
        put(requested, LimitType.DAILY_TRANSFER, input.dailyTransferLimit());
        put(requested, LimitType.MONTHLY_TRANSFER, input.monthlyTransferLimit());

        Limits current = account.getLimits();
        LimitPolicy.Plan plan = LimitPolicy.plan(current, requested);
        account.updateLimits(plan.immediate()); // conta não ativa: 422

        // reduzir (ou repetir o valor atual) cancela um aumento que estava pendente para o mesmo limite
        for (LimitType type : requested.keySet()) {
            if (!plan.increases().containsKey(type)) repository.replacePending(account.getId(), type, now);
        }
        Account saved = accountRepository.save(account);

        LocalDateTime effectiveAt = now.plus(LimitPolicy.INCREASE_DELAY);
        plan.increases().forEach((type, value) -> {
            repository.replacePending(saved.getId(), type, now);
            UUID id = UUID.randomUUID();
            repository.insert(new LimitChangeRequest(id, saved.getId(), type, current.get(type), value, now, effectiveAt,
                    Status.PENDING));
            eventPublisher.publishLimitIncreaseRequested(id, saved.getId(), type.label(), value.amount(), effectiveAt);
        });
        return view(saved);
    }

    @Transactional
    public View cancel(UUID accountId, UUID requestId) {
        if (!repository.cancel(requestId, accountId, now())) {
            throw new NotFoundException("Pedido de aumento de limite não encontrado ou já aplicado");
        }
        return view(find(accountId));
    }

    /**
     * Aplica na conta (em memória) os aumentos que já chegaram na hora e marca os pedidos. Quem chama
     * salva a conta, na transação dele.
     *
     * @return se algum limite mudou
     */
    public boolean applyDue(Account account, LocalDateTime now) {
        List<LimitChangeRequest> due = repository.findDue(account.getId(), now);
        if (due.isEmpty()) return false;
        Limits limits = account.getLimits();
        for (LimitChangeRequest request : due) {
            limits = LimitPolicy.applyIncrease(limits, request.type(), request.requestedAmount());
            repository.markApplied(request.id(), now);
        }
        account.applyScheduledLimits(limits);
        return true;
    }

    /** Para o job: aplica os vencidos de uma conta, na transação própria. */
    @Transactional
    public void applyDue(UUID accountId) {
        accountRepository.findById(accountId).ifPresent(account -> {
            if (applyDue(account, now())) accountRepository.save(account);
        });
    }

    public List<UUID> accountsWithDue(int limit) {
        return repository.accountsWithDue(now(), limit);
    }

    private View view(Account account) {
        Limits limits = account.getLimits();
        List<PendingView> pending = repository.findPending(account.getId()).stream()
                .map(r -> new PendingView(r.id(), r.type().name(), limits.get(r.type()).amount(), r.requestedAmount().amount(),
                        r.requestedAt(), r.effectiveAt()))
                .toList();
        return new View(account.getId(), limits.pixDailyLimit().amount(), limits.pixNightLimit().amount(),
                limits.dailyWithdrawalLimit().amount(), limits.dailyTransferLimit().amount(),
                limits.monthlyTransferLimit().amount(), pending);
    }

    private static void put(Map<LimitType, Money> requested, LimitType type, BigDecimal value) {
        if (value != null) requested.put(type, Money.of(value)); // negativo: o Money recusa (422)
    }

    private Account find(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
