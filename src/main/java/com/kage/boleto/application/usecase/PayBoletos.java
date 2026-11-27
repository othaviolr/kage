package com.kage.boleto.application.usecase;

import com.kage.boleto.domain.entity.Boleto;
import com.kage.boleto.domain.repository.BoletoPaymentRepository;
import com.kage.boleto.domain.repository.BoletoPaymentRepository.Kind;
import com.kage.boleto.domain.repository.BoletoPaymentRepository.Payment;
import com.kage.boleto.domain.repository.BoletoRepository;
import com.kage.boleto.domain.service.BillingBoletoGateway;
import com.kage.boleto.domain.service.BoletoAccountGateway;
import com.kage.boleto.domain.service.BoletoChargePolicy;
import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.DocumentMask;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lado do pagador: consultar um boleto pelo código e pagar.
 *
 * <ul>
 *   <li><b>Boleto do Kage</b> (código do nosso banco): o registro diz quem recebe, o valor e os
 *       encargos de hoje; o pagamento debita o pagador e credita o beneficiário na mesma transação.</li>
 *   <li><b>Boleto da fatura do cartão Kage</b> (o que a fatura gera para pagar de outro banco): pago
 *       aqui, o débito do pagador e o pagamento da fatura vão na mesma transação, sem esperar a
 *       compensação. Vale até o vencimento da fatura, sem encargos ({@link BillingBoletoGateway}).</li>
 *   <li><b>Boleto de outro banco</b>: o valor e o vencimento vêm do próprio código. O débito é na
 *       hora e o dinheiro segue para a compensação (simulada). Sem a consulta à CIP, encargos de
 *       atraso de boleto de fora não são calculados aqui.</li>
 * </ul>
 *
 * O mesmo código de barras não é pago duas vezes (índice único): é o erro mais comum com boleto.
 */
public class PayBoletos {

    static final int LIST_LIMIT = 50;
    public static final String VIA_KAGE = "KAGE";
    public static final String VIA_CLEARING = "COMPENSACAO";

    /** O que o pagador vê antes de confirmar. */
    public record Lookup(String barcode, String digitableLine, String bankCode, String kind, String beneficiaryName,
                         String beneficiaryDocument, String description, BigDecimal amount, LocalDate dueDate,
                         LocalDate payWithoutChargesUntil, BigDecimal fine, BigDecimal interest, BigDecimal total,
                         boolean amountRequired, boolean payable, String notPayableReason) {}

    public record PaymentView(UUID paymentId, String kind, String barcode, String beneficiaryName, LocalDate dueDate,
                              BigDecimal amount, BigDecimal fine, BigDecimal interest, BigDecimal paidAmount,
                              LocalDateTime paidAt) {}

    private final BoletoRepository boletos;
    private final BoletoPaymentRepository payments;
    private final BoletoAccountGateway accounts;
    private final BoletoEventPublisher events;
    private final BillingBoletoGateway billing;
    private final String bankCode;
    private final Clock clock;

    public PayBoletos(BoletoRepository boletos, BoletoPaymentRepository payments, BoletoAccountGateway accounts,
                      BoletoEventPublisher events, String bankCode, Clock clock) {
        this(boletos, payments, accounts, events, null, bankCode, clock);
    }

    public PayBoletos(BoletoRepository boletos, BoletoPaymentRepository payments, BoletoAccountGateway accounts,
                      BoletoEventPublisher events, BillingBoletoGateway billing, String bankCode, Clock clock) {
        this.billing = billing;
        this.boletos = boletos;
        this.payments = payments;
        this.accounts = accounts;
        this.events = events;
        this.bankCode = bankCode;
        this.clock = clock;
    }

    public Lookup lookup(String rawCode) {
        BoletoCode code = BoletoCode.parse(rawCode);
        LocalDate today = today();
        boolean alreadyPaid = payments.existsByBarcode(code.barcode());

        Optional<BillingBoletoGateway.Billing> bill = billingOf(code);
        if (bill.isPresent()) {
            BillingBoletoGateway.Billing b = bill.get();
            String reason = alreadyPaid ? "Boleto já foi pago" : b.notPayableReason();
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            return new Lookup(code.barcode(), code.formattedDigitableLine(), code.bankCode(), Kind.CARD_INVOICE.name(),
                    b.beneficiaryName(), null, b.description(), b.amount(), b.dueDate(), b.dueDate(), zero, zero,
                    b.amount(), false, reason == null, reason);
        }

        if (isOurs(code)) {
            Boleto boleto = registered(code);
            BoletoAccountGateway.AccountInfo beneficiary = accounts.account(boleto.getBeneficiaryAccountId()).orElseThrow();
            String reason = alreadyPaid || boleto.getStatus() == Boleto.Status.PAID ? "Boleto já foi pago" : null;
            BoletoChargePolicy.Charges charges = null;
            if (reason == null) {
                try {
                    charges = boleto.chargesOn(today);
                } catch (BusinessRuleException e) {
                    reason = e.getMessage();
                }
            }
            BigDecimal fine = charges == null ? null : charges.fine();
            BigDecimal interest = charges == null ? null : charges.interest();
            BigDecimal total = charges == null ? null : boleto.getAmount().amount().add(charges.total());
            return new Lookup(code.barcode(), code.formattedDigitableLine(), code.bankCode(), Kind.INTERNAL.name(),
                    beneficiary.holderName(), maskDocument(beneficiary.holderDocument()), boleto.getDescription(),
                    boleto.getAmount().amount(), boleto.getDueDate(), BoletoChargePolicy.graceDate(boleto.getDueDate()),
                    fine, interest, total, false, reason == null, reason);
        }

        BigDecimal face = code.amount();
        LocalDate due = code.dueDate(today).orElse(null);
        String reason = alreadyPaid ? "Boleto já foi pago" : null;
        return new Lookup(code.barcode(), code.formattedDigitableLine(), code.bankCode(), Kind.EXTERNAL.name(), null, null,
                null, face, due, due == null ? null : BoletoChargePolicy.graceDate(due), null, null,
                face.signum() == 0 ? null : face, face.signum() == 0, reason == null, reason);
    }

    /** @param amount só para boleto de outro banco sem valor impresso */
    @Transactional
    public PaymentView pay(UUID payerAccountId, String rawCode, BigDecimal amount) {
        BoletoCode code = BoletoCode.parse(rawCode);
        if (payments.existsByBarcode(code.barcode())) throw new BusinessRuleException("Boleto já foi pago");
        LocalDate today = today();
        UUID paymentId = UUID.randomUUID();
        Payment payment;
        Optional<BillingBoletoGateway.Billing> bill = billingOf(code);

        if (bill.isPresent()) {
            BillingBoletoGateway.Billing b = bill.get();
            if (b.notPayableReason() != null) throw new BusinessRuleException(b.notPayableReason());
            if (amount != null && amount.compareTo(b.amount()) != 0) {
                throw new ValidationException("Boleto com valor impresso: pague " + b.amount().toPlainString());
            }
            Money value = Money.of(b.amount());
            accounts.debitPayment(payerAccountId, value, paymentId);
            billing.settle(b.chargeId()); // a fatura recebe na hora, na mesma transação do débito
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            payment = new Payment(paymentId, payerAccountId, code.barcode(), code.bankCode(), Kind.CARD_INVOICE, null,
                    b.beneficiaryName(), b.dueDate(), value.amount(), zero, zero, value.amount(), LocalDateTime.now(clock));
            insert(payment);
        } else if (isOurs(code)) {
            Boleto boleto = registered(code);
            if (boleto.getBeneficiaryAccountId().equals(payerAccountId)) {
                throw new BusinessRuleException("Não dá para pagar um boleto emitido pela própria conta");
            }
            BoletoChargePolicy.Charges charges = boleto.chargesOn(today);
            Money total = boleto.pay(today, VIA_KAGE);
            accounts.debitPayment(payerAccountId, total, paymentId);
            accounts.creditReceived(boleto.getBeneficiaryAccountId(), total, boleto.getId());
            boletos.update(boleto);
            String beneficiaryName = accounts.account(boleto.getBeneficiaryAccountId()).map(BoletoAccountGateway.AccountInfo::holderName).orElse(null);
            payment = new Payment(paymentId, payerAccountId, code.barcode(), code.bankCode(), Kind.INTERNAL, boleto.getId(),
                    beneficiaryName, boleto.getDueDate(), boleto.getAmount().amount(), charges.fine(), charges.interest(),
                    total.amount(), LocalDateTime.now(clock));
            insert(payment);
            events.publishBoletoPaid(boleto);
        } else {
            Money value = externalAmount(code, amount);
            accounts.debitPayment(payerAccountId, value, paymentId);
            BigDecimal zero = BigDecimal.ZERO.setScale(2);
            payment = new Payment(paymentId, payerAccountId, code.barcode(), code.bankCode(), Kind.EXTERNAL, null, null,
                    code.dueDate(today).orElse(null), value.amount(), zero, zero, value.amount(), LocalDateTime.now(clock));
            insert(payment);
        }
        events.publishPaymentMade(payment);
        return view(payment);
    }

    public List<PaymentView> listPayments(UUID payerAccountId) {
        return payments.findByPayerAccount(payerAccountId, LIST_LIMIT).stream().map(PayBoletos::view).toList();
    }

    /** Backoffice: boleto do Kage pago em OUTRO banco chegou pela compensação. */
    @Transactional
    public IssueBoletos.BoletoView settleFromClearing(UUID boletoId) {
        Boleto boleto = boletos.findById(boletoId).orElseThrow(() -> new NotFoundException("Boleto não encontrado"));
        Money total = boleto.pay(today(), VIA_CLEARING);
        accounts.creditReceived(boleto.getBeneficiaryAccountId(), total, boleto.getId());
        boletos.update(boleto);
        events.publishBoletoPaid(boleto);
        return IssueBoletos.view(boleto, today());
    }

    private void insert(Payment payment) {
        try {
            payments.insert(payment);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Boleto já foi pago"); // corrida: outro pagamento entrou primeiro
        }
    }

    private Money externalAmount(BoletoCode code, BigDecimal informed) {
        BigDecimal face = code.amount();
        if (face.signum() == 0) {
            if (informed == null || informed.signum() <= 0) throw new ValidationException("Boleto sem valor: informe o valor a pagar");
            return Money.of(informed);
        }
        if (informed != null && informed.compareTo(face) != 0) {
            throw new ValidationException("Boleto com valor impresso: pague " + face.toPlainString());
        }
        return Money.of(face);
    }

    private Optional<BillingBoletoGateway.Billing> billingOf(BoletoCode code) {
        if (billing == null || !isOurs(code)) return Optional.empty();
        return billing.find(code.barcode());
    }

    private boolean isOurs(BoletoCode code) {
        return code.bankCode().equals(bankCode);
    }

    private Boleto registered(BoletoCode code) {
        Optional<Boleto> boleto = boletos.findByBarcode(code.barcode());
        // boleto com o nosso código mas sem registro: a CIP recusaria — não é pagável
        return boleto.orElseThrow(() -> new NotFoundException("Boleto não encontrado no Kage"));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    static String maskDocument(String document) {
        return DocumentMask.mask(document);
    }

    private static PaymentView view(Payment p) {
        return new PaymentView(p.id(), p.kind().name(), p.barcode(), p.beneficiaryName(), p.dueDate(), p.faceAmount(),
                p.fine(), p.interest(), p.paidAmount(), p.paidAt());
    }
}
