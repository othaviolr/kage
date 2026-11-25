package com.kage.boleto.application.usecase;

import com.kage.boleto.domain.entity.Boleto;
import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.boleto.domain.service.BoletoAccountGateway;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Lado do beneficiário: emitir, consultar e cancelar boletos para receber. */
public class IssueBoletos {

    static final int LIST_LIMIT = 50;

    public record Input(BigDecimal amount, LocalDate dueDate, String payerName, String payerDocument, String description,
                        BigDecimal finePercent, BigDecimal monthlyInterestPercent) {}

    public record BoletoView(UUID id, UUID beneficiaryAccountId, long ourNumber, String barcode, String digitableLine,
                             BigDecimal amount, LocalDate dueDate, LocalDate payableUntil, BigDecimal finePercent,
                             BigDecimal monthlyInterestPercent, String payerName, String description, String status,
                             BigDecimal amountDueToday, BigDecimal paidAmount, LocalDateTime paidAt, String paidVia,
                             LocalDateTime createdAt) {}

    private final BoletoRepository repository;
    private final BoletoAccountGateway accounts;
    private final String bankCode;
    private final Clock clock;

    public IssueBoletos(BoletoRepository repository, BoletoAccountGateway accounts, String bankCode, Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.bankCode = bankCode;
        this.clock = clock;
    }

    public BoletoView issue(UUID accountId, Input input) {
        BoletoAccountGateway.AccountInfo account = accounts.account(accountId)
                .orElseThrow(() -> new NotFoundException("Conta não encontrada"));
        if (!account.active()) throw new BusinessRuleException("Conta não está ativa");
        Boleto boleto = Boleto.issue(new Boleto.IssueRequest(accountId, account.branch(), account.accountNumber(),
                repository.nextOurNumber(), bankCode, input.amount() == null ? null : Money.of(input.amount()),
                input.dueDate(), input.finePercent(), input.monthlyInterestPercent(), input.payerName(),
                input.payerDocument(), input.description()), today());
        repository.insert(boleto);
        return view(boleto, today());
    }

    public BoletoView get(UUID boletoId) {
        return view(find(boletoId), today());
    }

    public List<BoletoView> listByAccount(UUID accountId) {
        LocalDate today = today();
        return repository.findByBeneficiaryAccount(accountId, LIST_LIMIT).stream().map(b -> view(b, today)).toList();
    }

    public BoletoView cancel(UUID boletoId) {
        Boleto boleto = find(boletoId);
        boleto.cancel();
        repository.update(boleto);
        return view(boleto, today());
    }

    private Boleto find(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Boleto não encontrado"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    static BoletoView view(Boleto b, LocalDate today) {
        String status = b.displayStatus(today);
        BigDecimal dueToday = status.equals("OPEN") || status.equals("OVERDUE") ? b.totalOn(today).amount() : null;
        return new BoletoView(b.getId(), b.getBeneficiaryAccountId(), b.getOurNumber(), b.getCode().barcode(),
                b.getCode().formattedDigitableLine(), b.getAmount().amount(), b.getDueDate(), b.getPayableUntil(),
                b.getFinePercent(), b.getMonthlyInterestPercent(), b.getPayerName(), b.getDescription(), status, dueToday,
                b.getPaidAmount() == null ? null : b.getPaidAmount().amount(), b.getPaidAt(), b.getPaidVia(), b.getCreatedAt());
    }
}
