package com.kage.savings.application.usecase;

import com.kage.savings.domain.entity.SavingsBox;
import com.kage.savings.domain.entity.SavingsLot;
import com.kage.savings.domain.repository.SavingsBoxRepository;
import com.kage.savings.domain.repository.SavingsBoxRepository.MovementType;
import com.kage.savings.domain.service.SavingsAccountGateway;
import com.kage.savings.domain.service.SavingsTaxPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * Resgate: o valor pedido sai BRUTO da caixinha; a conta recebe o líquido, depois de IOF e IR.
 */
public class SavingsBoxes {

    public static final int MAX_ACTIVE_PER_CUSTOMER = 20;
    static final int MOVEMENTS_SHOWN = 30;

    /** @param netBalance o que cairia na conta resgatando tudo hoje */
    public record BoxView(UUID id, UUID accountId, String name, BigDecimal balance, BigDecimal netBalance,
                          BigDecimal goalAmount, Integer goalProgressPercent, BigDecimal totalYield, String status,
                          LocalDate yieldCalculatedUntil, LocalDateTime goalReachedAt, LocalDateTime createdAt) {}
    public record MovementView(String type, BigDecimal amount, BigDecimal balanceAfter, LocalDateTime createdAt,
                               BigDecimal iof, BigDecimal incomeTax, BigDecimal netAmount) {}
    /** Um depósito ainda na caixinha, com as alíquotas que valeriam num resgate hoje. */
    public record LotView(LocalDate depositedOn, long daysHeld, BigDecimal principal, BigDecimal balance,
                          BigDecimal gain, BigDecimal iofRate, BigDecimal incomeTaxRate) {}
    public record Detail(BoxView box, List<LotView> lots, List<MovementView> movements) {}
    public record WithdrawalView(BigDecimal gross, BigDecimal gain, BigDecimal iof, BigDecimal incomeTax, BigDecimal net) {}
    public record WithdrawalResult(BoxView box, WithdrawalView withdrawal) {}

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
                today());
        repository.insert(box);
        return view(box);
    }

    public List<BoxView> listActive(UUID accountId) {
        return repository.findActiveByAccount(accountId).stream().map(this::view).toList();
    }

    public Detail get(UUID boxId) {
        SavingsBox box = find(boxId);
        LocalDate today = today();
        List<LotView> lots = box.getLots().stream().map(lot -> lotView(lot, today)).toList();
        List<MovementView> movements = repository.findMovements(boxId, MOVEMENTS_SHOWN).stream()
                .map(m -> new MovementView(m.type().name(), m.amount().amount(), m.balanceAfter().amount(), m.createdAt(),
                        amount(m.iof()), amount(m.incomeTax()), amount(m.netAmount())))
                .toList();
        return new Detail(view(box), lots, movements);
    }

    /** Simula um resgate (sem {@code amount}: tudo): quanto de IOF e IR sairia e quanto cairia na conta. */
    public WithdrawalView previewWithdrawal(UUID boxId, BigDecimal amount) {
        SavingsBox box = find(boxId);
        SavingsBox.Withdrawal w = amount == null ? box.previewFullWithdrawal(today())
                : box.previewWithdrawal(Money.of(amount), today());
        return withdrawalView(w);
    }

    /** Guardar: sai da conta, entra na caixinha como um lote novo. */
    @Transactional
    public BoxView deposit(UUID boxId, BigDecimal amount) {
        SavingsBox box = find(boxId);
        Money value = Money.of(amount);
        boolean goalReached = box.deposit(value, today()); // regra da caixinha primeiro: encerrada nem debita
        accounts.moveToSavings(box.getAccountId(), value, box.getId());
        repository.update(box);
        repository.insertMovement(box.getId(), MovementType.DEPOSIT, value, box.getBalance(), LocalDateTime.now(clock));
        if (goalReached) eventPublisher.publishGoalReached(box);
        return view(box);
    }

    /** Resgatar {@code amount} bruto: a conta recebe o líquido, na hora, sem carência. */
    @Transactional
    public WithdrawalResult withdraw(UUID boxId, BigDecimal amount) {
        SavingsBox box = find(boxId);
        SavingsBox.Withdrawal w = box.withdraw(Money.of(amount), today());
        return settle(box, w);
    }

    /** Resgata tudo (líquido de impostos) e encerra. */
    @Transactional
    public WithdrawalResult close(UUID boxId) {
        SavingsBox box = find(boxId);
        SavingsBox.Withdrawal w = box.getBalance().isZero() ? null : box.withdraw(box.getBalance(), today());
        box.close();
        if (w == null) {
            repository.update(box);
            return new WithdrawalResult(view(box), null);
        }
        return settle(box, w);
    }

    private WithdrawalResult settle(SavingsBox box, SavingsBox.Withdrawal w) {
        accounts.moveFromSavings(box.getAccountId(), w.net(), box.getId());
        repository.update(box);
        repository.insertWithdrawal(box.getId(), w, box.getBalance(), LocalDateTime.now(clock));
        return new WithdrawalResult(view(box), withdrawalView(w));
    }

    private SavingsBox find(UUID boxId) {
        return repository.findById(boxId).orElseThrow(() -> new NotFoundException("Caixinha não encontrada"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private BoxView view(SavingsBox b) {
        Integer progress = b.getGoalAmount() == null ? null
                : b.getBalance().amount().multiply(BigDecimal.valueOf(100))
                    .divide(b.getGoalAmount().amount(), 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue();
        BigDecimal net = b.getStatus() == SavingsBox.Status.ACTIVE ? b.previewFullWithdrawal(today()).net().amount()
                : BigDecimal.ZERO.setScale(2);
        return new BoxView(b.getId(), b.getAccountId(), b.getName(), b.getBalance().amount(), net,
                b.getGoalAmount() == null ? null : b.getGoalAmount().amount(), progress, b.getTotalYield().amount(),
                b.getStatus().name(), b.getLastYieldDate(), b.getGoalReachedAt(), b.getCreatedAt());
    }

    private static LotView lotView(SavingsLot lot, LocalDate today) {
        long days = lot.daysHeld(today);
        return new LotView(lot.getDepositedOn(), days, lot.getPrincipal(), lot.getBalance(), lot.gain(),
                SavingsTaxPolicy.iofRate(days), SavingsTaxPolicy.incomeTaxRate(days));
    }

    private static WithdrawalView withdrawalView(SavingsBox.Withdrawal w) {
        return new WithdrawalView(w.gross().amount(), w.gain().amount(), w.iof().amount(), w.incomeTax().amount(),
                w.net().amount());
    }

    private static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }
}
