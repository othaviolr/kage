package com.kage.savings.application.usecase;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.repository.SavingsBoxRepository.MovementType;
import com.kage.savings.domain.valueobject.CdiSchedule;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Rende UMA caixinha, na própria transação (bean separado do laço do RunSavingsYield: @Transactional
 * chamado de dentro da mesma classe não abre transação nova).
 */
public class SavingsYieldExecutor {

    private final SavingsBoxRepository repository;
    private final SavingsEventPublisher eventPublisher;
    private final Clock clock;

    public SavingsYieldExecutor(SavingsBoxRepository repository, SavingsEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /** @return o que foi creditado */
    @Transactional
    public Money accrue(UUID boxId, CdiSchedule cdi) {
        SavingsBox box = repository.findById(boxId).orElse(null);
        if (box == null) return Money.ZERO;

        boolean hadReachedGoal = box.getGoalReachedAt() != null;
        Money credited = box.accrueYield(cdi);
        repository.update(box); // também avança last_yield_date quando não rendeu centavo inteiro

        if (!credited.isZero()) {
            repository.insertMovement(box.getId(), MovementType.YIELD, credited, box.getBalance(), LocalDateTime.now(clock));
        }
        // o rendimento também pode completar a meta
        if (!hadReachedGoal && box.getGoalReachedAt() != null) eventPublisher.publishGoalReached(box);
        return credited;
    }
}
