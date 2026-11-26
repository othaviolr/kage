package com.kage.loan.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.loan.application.usecase.LoanDischargeLetters;
import com.kage.loan.application.usecase.PersonalLoans;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Empréstimo pessoal da conta. O cliente só mexe nas próprias contas (AccessGuard). */
@RestController
@RequestMapping("/api/accounts/{accountId}/loans")
public class LoanController {

    private final PersonalLoans personalLoans;
    private final LoanDischargeLetters dischargeLetters;
    private final AccessGuard accessGuard;

    public LoanController(PersonalLoans personalLoans, LoanDischargeLetters dischargeLetters, AccessGuard accessGuard) {
        this.personalLoans = personalLoans;
        this.dischargeLetters = dischargeLetters;
        this.accessGuard = accessGuard;
    }

    /** Quanto dá para pegar: parcela de até 30% da renda estimada pela movimentação da conta. */
    @GetMapping("/offer")
    public ResponseEntity<PersonalLoans.Offer> offer(@PathVariable UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.offer(accountId));
    }

    /** Simulação: parcelas, IOF, total e CET. Não muda nada. */
    @GetMapping("/simulation")
    public ResponseEntity<PersonalLoans.Simulation> simulate(@PathVariable UUID accountId, @RequestParam BigDecimal amount,
                                                             @RequestParam Integer installments,
                                                             @RequestParam(required = false) Integer dueDay) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.simulate(accountId, amount, installments, dueDay));
    }

    /** Contrata: o valor cai na conta na hora. */
    @PostMapping
    public ResponseEntity<PersonalLoans.Output> contract(@PathVariable UUID accountId, @RequestBody LoanRequest request) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(personalLoans.contract(accountId, request.amount(), request.installments(), request.dueDay()));
    }

    @GetMapping
    public ResponseEntity<List<PersonalLoans.Output>> list(@PathVariable UUID accountId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.list(accountId));
    }

    @GetMapping("/{loanId}")
    public ResponseEntity<PersonalLoans.Output> get(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.get(accountId, loanId));
    }

    /** Paga a próxima parcela agora (antes do vencimento, com desconto dos juros; atrasada, com encargos). */
    @PostMapping("/{loanId}/installments/pay")
    public ResponseEntity<PersonalLoans.Payment> payNext(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.payNext(accountId, loanId));
    }

    @GetMapping("/{loanId}/payoff")
    public ResponseEntity<PersonalLoans.Payment> payoffQuote(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.payoffQuote(accountId, loanId));
    }

    @PostMapping("/{loanId}/payoff")
    public ResponseEntity<PersonalLoans.Payment> payOff(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.payOff(accountId, loanId));
    }

    /**
     * Proposta de renegociação da dívida em atraso: o que se deve hoje, a entrada e as parcelas novas (com
     * IOF complementar e CET). Não muda nada.
     */
    @GetMapping("/{loanId}/renegotiation")
    public ResponseEntity<PersonalLoans.Renegotiation> renegotiationQuote(@PathVariable UUID accountId,
                                                                         @PathVariable UUID loanId,
                                                                         @RequestParam Integer installments,
                                                                         @RequestParam(required = false) Integer dueDay,
                                                                         @RequestParam(required = false) BigDecimal downPayment) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.renegotiationQuote(accountId, loanId, installments, dueDay, downPayment));
    }

    /** Fecha o acordo: a entrada sai da conta e o acordo substitui o empréstimo atrasado. */
    @PostMapping("/{loanId}/renegotiation")
    public ResponseEntity<PersonalLoans.Output> renegotiate(@PathVariable UUID accountId, @PathVariable UUID loanId,
                                                            @RequestBody RenegotiationRequest request) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.status(HttpStatus.CREATED).body(personalLoans.renegotiate(accountId, loanId,
                request.installments(), request.dueDay(), request.downPayment()));
    }

    /** @param downPayment entrada (opcional), debitada da conta na hora */
    public record RenegotiationRequest(Integer installments, Integer dueDay, BigDecimal downPayment) {
    }

    /** Carta de quitação em PDF: só de empréstimo (ou acordo) quitado. */
    @GetMapping("/{loanId}/discharge-letter")
    public ResponseEntity<byte[]> dischargeLetter(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        LoanDischargeLetters.Document document = dischargeLetters.pdf(accountId, loanId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.fileName()).build().toString())
                .body(document.content());
    }

    /** Arrependimento (CDC, art. 49): em até 7 dias, devolve o que recebeu, sem juros nem IOF. */
    @PostMapping("/{loanId}/withdrawal")
    public ResponseEntity<PersonalLoans.Payment> withdraw(@PathVariable UUID accountId, @PathVariable UUID loanId) {
        accessGuard.checkAccount(accountId);
        return ResponseEntity.ok(personalLoans.withdraw(accountId, loanId));
    }

    /** @param dueDay dia de vencimento (1 a 28); omitido = 10 */
    public record LoanRequest(BigDecimal amount, Integer installments, Integer dueDay) {
    }
}
