package com.kage.card.infrastructure.controller;

import com.kage.card.application.usecase.*;
import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final IssueCard issueCard;
    private final GetCard getCard;
    private final BlockCard blockCard;
    private final UnblockCard unblockCard;
    private final RegisterPurchase registerPurchase;
    private final GetInvoice getInvoice;
    private final ListInvoices listInvoices;
    private final CloseInvoice closeInvoice;
    private final CancelCard cancelCard;
    private final AccessGuard accessGuard;
    private final PayInvoice payInvoice;
    private final SetAutoDebit setAutoDebit;
    private final FinanceInvoice financeInvoice;
    private final AnticipateInstallments anticipateInstallments;

    /**
     * Emissão é do ADMIN: o limite de crédito é decisão da análise de crédito do banco, não do cliente.
     * Compras chegam pela bandeira e o fechamento é job do banco (ambos ADMIN no SecurityConfig).
     * O cliente consulta, bloqueia/desbloqueia, cancela e vê as faturas dos próprios cartões.
     */
    public CardController(IssueCard issueCard, GetCard getCard, BlockCard blockCard,
                          UnblockCard unblockCard, RegisterPurchase registerPurchase,
                          GetInvoice getInvoice, ListInvoices listInvoices,
                          CloseInvoice closeInvoice, CancelCard cancelCard, AccessGuard accessGuard,
                          PayInvoice payInvoice, SetAutoDebit setAutoDebit, FinanceInvoice financeInvoice,
                          AnticipateInstallments anticipateInstallments) {
        this.anticipateInstallments = anticipateInstallments;
        this.financeInvoice = financeInvoice;
        this.accessGuard = accessGuard;
        this.payInvoice = payInvoice;
        this.setAutoDebit = setAutoDebit;
        this.issueCard = issueCard;
        this.getCard = getCard;
        this.blockCard = blockCard;
        this.unblockCard = unblockCard;
        this.registerPurchase = registerPurchase;
        this.getInvoice = getInvoice;
        this.listInvoices = listInvoices;
        this.closeInvoice = closeInvoice;
        this.cancelCard = cancelCard;
    }

    @PostMapping
    public ResponseEntity<IssueCard.Output> issue(@RequestBody IssueCard.Input input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issueCard.execute(input));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GetCard.Output> getById(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(getCard.execute(new GetCard.Input(id)));
    }

    @PatchMapping("/{id}/block")
    public ResponseEntity<BlockCard.Output> block(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(blockCard.execute(new BlockCard.Input(id)));
    }

    @PatchMapping("/{id}/unblock")
    public ResponseEntity<UnblockCard.Output> unblock(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(unblockCard.execute(new UnblockCard.Input(id)));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<CancelCard.Output> cancel(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(cancelCard.execute(new CancelCard.Input(id)));
    }

    @PostMapping("/{id}/purchases")
    public ResponseEntity<RegisterPurchase.Output> registerPurchase(@PathVariable UUID id,
                                                                     @RequestBody PurchaseRequest request) {
        LocalDateTime purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : LocalDateTime.now();
        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                id, request.purchaseId(), request.description(), request.amount(), purchasedAt, request.installments()));
        return ResponseEntity.status(HttpStatus.CREATED).body(output);
    }

    /** Simulação do parcelamento da fatura: não muda nada. */
    @GetMapping("/{id}/invoices/{referenceMonth}/installment-plan")
    public ResponseEntity<FinanceInvoice.Output> quoteInstallmentPlan(@PathVariable UUID id, @PathVariable String referenceMonth,
                                                                      @RequestParam int installments) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(financeInvoice.quote(id, referenceMonth, installments));
    }

    /** Contrata o parcelamento: a fatura vira FINANCED e as parcelas entram nas faturas seguintes. */
    @PostMapping("/{id}/invoices/{referenceMonth}/installment-plan")
    public ResponseEntity<FinanceInvoice.Output> financeInvoice(@PathVariable UUID id, @PathVariable String referenceMonth,
                                                                @RequestBody InstallmentPlanRequest request) {
        accessGuard.checkCard(id);
        if (request.installments() == null) throw new ValidationException("Informe o número de parcelas (installments)");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeInvoice.execute(id, referenceMonth, request.installments()));
    }

    public record InstallmentPlanRequest(Integer installments) {
    }

    /** Compras parceladas e parcelamentos de fatura com parcelas em faturas futuras, e quanto custa antecipar. */
    @GetMapping("/{id}/installment-plans")
    public ResponseEntity<List<AnticipateInstallments.Plan>> listInstallmentPlans(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(anticipateInstallments.listPlans(id));
    }

    /** Simulação da antecipação das últimas {@code installments} parcelas: não muda nada. */
    @GetMapping("/{id}/installment-plans/{planId}/anticipation")
    public ResponseEntity<AnticipateInstallments.Output> quoteAnticipation(@PathVariable UUID id, @PathVariable UUID planId,
                                                                           @RequestParam int installments) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(anticipateInstallments.quote(id, planId, installments));
    }

    /** Antecipa: as parcelas saem das faturas futuras e entram na fatura atual, com o desconto dos juros. */
    @PostMapping("/{id}/installment-plans/{planId}/anticipation")
    public ResponseEntity<AnticipateInstallments.Output> anticipate(@PathVariable UUID id, @PathVariable UUID planId,
                                                                    @RequestBody InstallmentPlanRequest request) {
        accessGuard.checkCard(id);
        if (request.installments() == null) throw new ValidationException("Informe quantas parcelas antecipar (installments)");
        return ResponseEntity.status(HttpStatus.CREATED).body(anticipateInstallments.execute(id, planId, request.installments()));
    }

    @GetMapping("/{id}/invoices")
    public ResponseEntity<ListInvoices.Output> listInvoices(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(listInvoices.execute(new ListInvoices.Input(id)));
    }

    @GetMapping("/{id}/invoices/{referenceMonth}")
    public ResponseEntity<GetInvoice.Output> getInvoice(@PathVariable UUID id, @PathVariable String referenceMonth) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(getInvoice.execute(new GetInvoice.Input(id, referenceMonth)));
    }

    /** Liga/desliga o pagamento automático da fatura no vencimento, com o saldo da conta vinculada. */
    @PatchMapping("/{id}/auto-debit")
    public ResponseEntity<SetAutoDebit.Output> setAutoDebit(@PathVariable UUID id, @RequestBody AutoDebitRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(setAutoDebit.execute(new SetAutoDebit.Input(id, request.enabled())));
    }

    public record AutoDebitRequest(Boolean enabled) {
    }

    /** Paga a fatura fechada (total ou parcial) com o saldo da conta vinculada ao cartão. */
    @PostMapping("/{id}/invoices/{referenceMonth}/pay")
    public ResponseEntity<PayInvoice.Output> payInvoice(@PathVariable UUID id, @PathVariable String referenceMonth,
                                                        @RequestBody(required = false) PayInvoiceRequest request) {
        accessGuard.checkCard(id);
        BigDecimal amount = request == null ? null : request.amount();
        return ResponseEntity.ok(payInvoice.execute(new PayInvoice.Input(id, referenceMonth, amount)));
    }

    /** Sem corpo (ou sem amount): paga o saldo devedor inteiro. Com amount: pagamento parcial. */
    public record PayInvoiceRequest(BigDecimal amount) {
    }

    @PatchMapping("/{id}/invoices/{referenceMonth}/close")
    public ResponseEntity<CloseInvoice.Output> closeInvoice(@PathVariable UUID id, @PathVariable String referenceMonth) {
        return ResponseEntity.ok(closeInvoice.execute(new CloseInvoice.Input(id, referenceMonth)));
    }

    /** @param installments parcelas sem juros (1 a 12); omitido = à vista */
    public record PurchaseRequest(UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                                  Integer installments) {
    }
}
