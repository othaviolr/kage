package com.kage.account.infrastructure.controller;

import com.kage.account.application.usecase.*;
import com.kage.account.infrastructure.messaging.AccountEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final CreateAccount createAccount;
    private final GetAccount getAccount;
    private final BlockAccount blockAccount;
    private final UnblockAccount unblockAccount;
    private final CloseAccount closeAccount;
    private final UpdateLimits updateLimits;
    private final DepositAccount depositAccount;
    private final AccountEventPublisher accountEventPublisher;

    public AccountController(CreateAccount createAccount, GetAccount getAccount,
                             BlockAccount blockAccount, UnblockAccount unblockAccount,
                             CloseAccount closeAccount, UpdateLimits updateLimits,
                             DepositAccount depositAccount, AccountEventPublisher accountEventPublisher) {
        this.createAccount = createAccount;
        this.getAccount = getAccount;
        this.blockAccount = blockAccount;
        this.unblockAccount = unblockAccount;
        this.closeAccount = closeAccount;
        this.updateLimits = updateLimits;
        this.depositAccount = depositAccount;
        this.accountEventPublisher = accountEventPublisher;
    }

    @PostMapping
    public ResponseEntity<CreateAccount.Output> create(@RequestBody CreateAccount.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(createAccount.execute(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetAccount.Output> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(getAccount.execute(new GetAccount.Input(id)));
    }

    @PostMapping("/{id}/deposit")
    public ResponseEntity<DepositAccount.Output> deposit(@PathVariable UUID id, @RequestBody DepositRequest request) {
        DepositAccount.Output output = depositAccount.execute(new DepositAccount.Input(id, request.amount()));
        accountEventPublisher.publishDepositMade(output, request.amount());
        return ResponseEntity.ok(output);
    }

    public record DepositRequest(BigDecimal amount) {
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
        return ResponseEntity.ok(closeAccount.execute(new CloseAccount.Input(id)));
    }

    @PatchMapping("/{id}/limits")
    public ResponseEntity<UpdateLimits.Output> updateLimits(@PathVariable UUID id,
                                                            @RequestBody LimitsRequest request) {
        return ResponseEntity.ok(updateLimits.execute(new UpdateLimits.Input(
                id, request.dailyTransferLimit(), request.monthlyTransferLimit(), request.pixDailyLimit(), request.pixNightLimit())));
    }

    public record LimitsRequest(BigDecimal dailyTransferLimit, BigDecimal monthlyTransferLimit,
                                BigDecimal pixDailyLimit, BigDecimal pixNightLimit) {
    }
}