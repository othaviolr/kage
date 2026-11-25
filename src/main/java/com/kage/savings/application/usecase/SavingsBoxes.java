package com.kage.savings.application.usecase;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.repository.SavingsBoxRepository.MovementType;
import com.kage.savings.domain.service.SavingsAccountGateway;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Criar, consultar, guardar, resgatar e encerrar caixinhas.
 *
 * Guardar e resgatar mexem na conta e na caixinha na MESMA transação (o gateway do Account debita /
 * credita com lançamento no extrato): ou as duas mudam, ou nenhuma. O @Version da conta e o da
 * caixinha derrubam a corrida entre duas operações simultâneas (409).
 */
public class SavingsBoxes {

    public static final int MAX_ACTIVE_PER_CUSTOMER = 20;
    static final int MOVEMENTS_SHOWN = 30;

    public record BoxView(UUID id, UUID accountId, String name, BigDecimal balance, BigDecimal goalAmount,
                          Integer goalProgressPercent, BigDecimal totalYield, String status, LocalDate yieldCalculatedUntil,
                          LocalDateTime goalReachedAt, LocalDateTime createdAt) {}
    public record MovementView(String type, BigDecimal amount, BigDecimal balanceAfter, LocalDateTime createdAt) {}
    public record Detail(BoxView box, List<MovementView> movements) {}

    private final SavingsBoxRepository repository;
    private final SavingsAccountGateway accounts;
    private final SavingsEventPublisher eventPublisher;
    private final Clock clock;

    public SavingsBoxes(SavingsBoxRepository repository, SavingsAccountGateway accounts,
                        SavingsEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public BoxView create(UUID accountId, String name, BigDecimal goalAmount) {
        UUID customerId = accounts.customerOf(accountId).orElseThrow(() -> new NotFoundException("Conta não encontrada"));
        if (repository.countActiveByCustomer(customerId) >= MAX_ACTIVE_PER_CUSTOMER) {
            throw new BusinessRuleException("Limite de " + MAX_ACTIVE_PER_CUSTOMER + " caixinhas ativas atingido");
        }
        SavingsBox box = SavingsBox.create(customerId, accountId, name, goalAmount == null ? null : Money.of(goalAmount),
                LocalDate.now(clock));
        repository.insert(box);
        return view(box);
    }

    public List<BoxView> listActive(UUID accountId) {
        return repository.findActiveByAccount(accountId).stream().map(SavingsBoxes::view).toList();
    }

    public Detail get(UUID boxId) {
        SavingsBox box = find(boxId);
        List<MovementView> movements = repository.findMovements(boxId, MOVEMENTS_SHOWN).stream()
                .map(m -> new MovementView(m.type().name(), m.amount().amount(), m.balanceAfter().amount(), m.createdAt()))
                .toList();
        return new Detail(view(box), movements);
    }

    /** Guardar: sai da conta, entra na caixinha. */
    @Transactional
    public BoxView deposit(UUID boxId, BigDecimal amount) {
        SavingsBox box = find(boxId);
        Money value = Money.of(amount);
        boolean goalReached = box.deposit(value);           // regra da caixinha primeiro: encerrada nem debita
        accounts.moveToSavings(box.getAccountId(), value, box.getId());
        repository.update(box);
        repository.insertMovement(box.getId(), MovementType.DEPOSIT, value, box.getBalance(), LocalDateTime.now(clock));
        if (goalReached) eventPublisher.publishGoalReached(box);
        return view(box);
    }

    /** Resgatar: sai da caixinha, volta para a conta. Na hora, sem carência. */
    @Transactional
    public BoxView withdraw(UUID boxId, BigDecimal amount) {
        SavingsBox box = find(boxId);
        Money value = Money.of(amount);
        box.withdraw(value);
        accounts.moveFromSavings(box.getAccountId(), value, box.getId());
        repository.update(box);
        repository.insertMovement(box.getId(), MovementType.WITHDRAWAL, value, box.getBalance(), LocalDateTime.now(clock));
        return view(box);
    }

    /** Resgata tudo e encerra. */
    @Transactional
    public BoxView close(UUID boxId) {
        SavingsBox box = find(boxId);
        Money remaining = box.close();
        if (!remaining.isZero()) {
            accounts.moveFromSavings(box.getAccountId(), remaining, box.getId());
            repository.insertMovement(box.getId(), MovementType.WITHDRAWAL, remaining, Money.ZERO, LocalDateTime.now(clock));
        }
        repository.update(box);
        return view(box);
    }

    private SavingsBox find(UUID boxId) {
        return repository.findById(boxId).orElseThrow(() -> new NotFoundException("Caixinha não encontrada"));
    }

    static BoxView view(SavingsBox b) {
        Integer progress = b.getGoalAmount() == null ? null
                : b.getBalance().amount().multiply(BigDecimal.valueOf(100))
                    .divide(b.getGoalAmount().amount(), 0, java.math.RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
        return new BoxView(b.getId(), b.getAccountId(), b.getName(), b.getBalance().amount(),
                b.getGoalAmount() == null ? null : b.getGoalAmount().amount(), progress, b.getTotalYield().amount(),
                b.getStatus().name(), b.getLastYieldDate(), b.getGoalReachedAt(), b.getCreatedAt());
    }
}
