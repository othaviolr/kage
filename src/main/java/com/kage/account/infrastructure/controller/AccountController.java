package com.kage.account.infrastructure.controller;

import com.kage.account.application.usecase.*;
import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.account.infrastructure.export.StatementCsv;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Deposit, block e unblock são só do ADMIN (SecurityConfig). O resto exige que a conta seja do
 * cliente autenticado (AccessGuard).
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final CreateAccount createAccount;
    private final GetAccount getAccount;
    private final BlockAccount blockAccount;
    private final UnblockAccount unblockAccount;
    private final CloseAccount closeAccount;
    private final LimitChanges limitChanges;
    private final DepositAccount depositAccount;
    private final WithdrawAccount withdrawAccount;
    private final GetStatement getStatement;
    private final AccessGuard accessGuard;

    public AccountController(CreateAccount createAccount, GetAccount getAccount,
                             BlockAccount blockAccount, UnblockAccount unblockAccount,
                             CloseAccount closeAccount, LimitChanges limitChanges,
                             DepositAccount depositAccount, WithdrawAccount withdrawAccount,
                             GetStatement getStatement, AccessGuard accessGuard) {
        this.createAccount = createAccount;
        this.getAccount = getAccount;
        this.blockAccount = blockAccount;
        this.unblockAccount = unblockAccount;
        this.closeAccount = closeAccount;
        this.limitChanges = limitChanges;
        this.depositAccount = depositAccount;
        this.withdrawAccount = withdrawAccount;
        this.getStatement = getStatement;
        this.accessGuard = accessGuard;
    }

    @PostMapping
    public ResponseEntity<CreateAccount.Output> create(@RequestBody CreateAccount.Input input) {
        accessGuard.checkCustomer(input.customerId()); // cliente só abre conta para si mesmo
        return ResponseEntity.status(HttpStatus.CREATED).body(createAccount.execute(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetAccount.Output> getById(@PathVariable UUID id) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(getAccount.execute(new GetAccount.Input(id)));
    }

    @GetMapping("/{id}/statement")
    public ResponseEntity<GetStatement.Output> statement(@PathVariable UUID id,
                                                         @RequestParam(required = false) LocalDate from,
                                                         @RequestParam(required = false) LocalDate to) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(getStatement.execute(new GetStatement.Input(id, from, to)));
    }

    /**
     * Extrato em CSV (até 1 ano), para abrir no Excel em português: ';', vírgula decimal, BOM UTF-8,
     * saldo anterior e final. Sem datas, os últimos 30 dias.
     */
    @GetMapping("/{id}/statement.csv")
    public ResponseEntity<byte[]> statementCsv(@PathVariable UUID id,
                                               @RequestParam(required = false) LocalDate from,
                                               @RequestParam(required = false) LocalDate to) {
        accessGuard.checkAccount(id);
        GetStatement.Output statement = getStatement.forExport(new GetStatement.Input(id, from, to));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, StatementCsv.CONTENT_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(StatementCsv.fileName(statement)).build().toString())
                .body(StatementCsv.write(statement).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<DepositAccount.Output> deposit(@PathVariable UUID id, @RequestBody DepositRequest request) {
        return ResponseEntity.ok(depositAccount.execute(new DepositAccount.Input(id, request.amount())));
    }

    public record DepositRequest(BigDecimal amount) {
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<WithdrawAccount.Output> withdraw(@PathVariable UUID id, @RequestBody WithdrawRequest request) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(withdrawAccount.execute(new WithdrawAccount.Input(id, request.amount())));
    }

    public record WithdrawRequest(BigDecimal amount) {
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<BlockAccount.Output> block(@PathVariable UUID id) {
        return ResponseEntity.ok(blockAccount.execute(new BlockAccount.Input(id)));
    }

    @PatchMapping("/{id}/unblock")
    public ResponseEntity<UnblockAccount.Output> unblock(@PathVariable UUID id) {
        return ResponseEntity.ok(unblockAccount.execute(new UnblockAccount.Input(id)));
    }

    @PatchMapping("/{id}/close")
    public ResponseEntity<CloseAccount.Output> close(@PathVariable UUID id) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(closeAccount.execute(new CloseAccount.Input(id)));
    }

    /** Limites que valem agora e os aumentos pendentes (com a hora em que entram em vigor). */
    @GetMapping("/{id}/limits")
    public ResponseEntity<LimitChanges.View> limits(@PathVariable UUID id) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(limitChanges.view(id));
    }

    /**
     * Campos omitidos ficam como estão. Reduzir vale na hora; aumentar vira pedido que vale 24h depois
     * (o cliente é avisado e pode cancelar).
     */
    @PatchMapping("/{id}/limits")
    public ResponseEntity<LimitChanges.View> updateLimits(@PathVariable UUID id,
                                                          @RequestBody LimitsRequest request) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(limitChanges.change(new LimitChanges.Input(id, request.pixDailyLimit(),
                request.pixNightLimit(), request.dailyWithdrawalLimit(), request.dailyTransferLimit(),
                request.monthlyTransferLimit())));
    }

    /** Desiste de um aumento antes de ele valer. */
    @DeleteMapping("/{id}/limits/requests/{requestId}")
    public ResponseEntity<LimitChanges.View> cancelLimitIncrease(@PathVariable UUID id, @PathVariable UUID requestId) {
        accessGuard.checkAccount(id);
        return ResponseEntity.ok(limitChanges.cancel(id, requestId));
    }

    public record LimitsRequest(BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit,
                                BigDecimal pixDailyLimit, BigDecimal pixNightLimit, BigDecimal dailyWithdrawalLimit) {
    }
}
