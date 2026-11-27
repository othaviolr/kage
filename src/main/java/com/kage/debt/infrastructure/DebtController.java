package com.kage.debt.infrastructure;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.debt.application.MyDebts;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** "Minhas dívidas": tudo o que o cliente deve no Kage (cartões e empréstimos), com o custo de cada uma. */
@RestController
public class DebtController {

    private final MyDebts myDebts;
    private final AccessGuard accessGuard;

    public DebtController(MyDebts myDebts, AccessGuard accessGuard) {
        this.myDebts = myDebts;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/customers/{customerId}/debts")
    public ResponseEntity<MyDebts.Output> get(@PathVariable UUID customerId) {
        accessGuard.checkCustomer(customerId);
        return ResponseEntity.ok(myDebts.execute(customerId));
    }
}
