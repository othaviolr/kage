package com.kage.card.infrastructure.controller;

import com.kage.shared.domain.DomainTime;
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
    private final DisputePurchase disputePurchase;
    private final VirtualCards virtualCards;

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
                          AnticipateInstallments anticipateInstallments, DisputePurchase disputePurchase,
                          VirtualCards virtualCards) {
        this.virtualCards = virtualCards;
        this.anticipateInstallments = anticipateInstallments;
        this.disputePurchase = disputePurchase;
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
        LocalDateTime purchasedAt = request.purchasedAt() != null ? request.purchasedAt() : DomainTime.now();
        RegisterPurchase.Output output = registerPurchase.execute(new RegisterPurchase.Input(
                id, request.purchaseId(), request.description(), request.amount(), purchasedAt, request.installments(),
                request.virtualCardNumber()));
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

    /**
     * Contesta uma compra (até 120 dias depois dela): a cobrança fica suspensa durante a análise.
     * Motivo NOT_RECOGNIZED bloqueia o cartão.
     */
    @PostMapping("/{id}/disputes")
    public ResponseEntity<DisputePurchase.Output> openDispute(@PathVariable UUID id, @RequestBody DisputeRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(disputePurchase.open(
                new DisputePurchase.Input(id, request.purchaseId(), request.reason(), request.details())));
    }

    @GetMapping("/{id}/disputes")
    public ResponseEntity<List<DisputePurchase.Output>> listDisputes(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(disputePurchase.list(id));
    }

    @GetMapping("/{id}/disputes/{disputeId}")
    public ResponseEntity<DisputePurchase.Output> getDispute(@PathVariable UUID id, @PathVariable UUID disputeId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(disputePurchase.get(id, disputeId));
    }

    /** Parecer do banco/bandeira (ADMIN no SecurityConfig). Negada, a compra volta a ser cobrada. */
    @PatchMapping("/{id}/disputes/{disputeId}/resolve")
    public ResponseEntity<DisputePurchase.Output> resolveDispute(@PathVariable UUID id, @PathVariable UUID disputeId,
                                                                 @RequestBody ResolveDisputeRequest request) {
        return ResponseEntity.ok(disputePurchase.resolve(id, disputeId, request.accepted(), request.note()));
    }

    /** @param reason NOT_RECOGNIZED, DUPLICATE_CHARGE, NOT_RECEIVED ou CANCELLED_PURCHASE */
    public record DisputeRequest(UUID purchaseId, String reason, String details) {
    }

    /** @param note obrigatório quando negada: o cliente fica sabendo o motivo */
    public record ResolveDisputeRequest(Boolean accepted, String note) {
    }

    /** Cria um cartão virtual (PERMANENT, o padrão, ou TEMPORARY de 24h). A resposta já traz número e CVV. */
    @PostMapping("/{id}/virtual-cards")
    public ResponseEntity<VirtualCards.Details> createVirtualCard(@PathVariable UUID id, @RequestBody VirtualCardRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(virtualCards.create(new VirtualCards.Input(id, request.nickname(), request.kind())));
    }

    /** Lista com o número mascarado (apagados e expirados inclusive, para o histórico). */
    @GetMapping("/{id}/virtual-cards")
    public ResponseEntity<List<VirtualCards.Output>> listVirtualCards(@PathVariable UUID id) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(virtualCards.list(id));
    }

    /** Número completo, CVV e validade, para preencher no site. */
    @GetMapping("/{id}/virtual-cards/{virtualCardId}")
    public ResponseEntity<VirtualCards.Details> virtualCardDetails(@PathVariable UUID id, @PathVariable UUID virtualCardId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(virtualCards.details(id, virtualCardId));
    }

    @PatchMapping("/{id}/virtual-cards/{virtualCardId}/block")
    public ResponseEntity<VirtualCards.Output> blockVirtualCard(@PathVariable UUID id, @PathVariable UUID virtualCardId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(virtualCards.block(id, virtualCardId));
    }

    @PatchMapping("/{id}/virtual-cards/{virtualCardId}/unblock")
    public ResponseEntity<VirtualCards.Output> unblockVirtualCard(@PathVariable UUID id, @PathVariable UUID virtualCardId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(virtualCards.unblock(id, virtualCardId));
    }

    /** Apaga de vez: o número deixa de funcionar. Não afeta o físico nem os outros virtuais. */
    @DeleteMapping("/{id}/virtual-cards/{virtualCardId}")
    public ResponseEntity<VirtualCards.Output> deleteVirtualCard(@PathVariable UUID id, @PathVariable UUID virtualCardId) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(virtualCards.delete(id, virtualCardId));
    }

    /** @param kind PERMANENT (padrão) ou TEMPORARY (expira em 24h) */
    public record VirtualCardRequest(String nickname, String kind) {
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

    /**
     * @param installments      parcelas sem juros (1 a 12); omitido = à vista
     * @param virtualCardNumber compra online com o número de um cartão virtual deste cartão; omitido = o físico
     */
    public record PurchaseRequest(UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                                  Integer installments, String virtualCardNumber) {
    }
}
