package com.kage.card.application.usecase;

import com.kage.boleto.domain.valueobject.BoletoCode;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.entity.InvoiceCharge;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceChargeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.PixBrCode;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Pagar a fatura de outro banco. O cliente gera, para a fatura fechada, um <b>boleto</b> (vale até o
 * vencimento da fatura; compensa em até 3 dias úteis) ou um <b>PIX copia e cola</b> (vale 24 horas; cai
 * na hora), do saldo devedor ou de parte dele. Gerar um código novo cancela o anterior da mesma forma
 * de pagamento: só um vale por vez.
 *
 * A fatura só recebe o pagamento quando o dinheiro chega ({@link #settle}: a compensação do boleto ou a
 * liquidação do PIX — no Kage, simuladas pelo backoffice). Vale a data em que o cliente pagou lá.
 * O dinheiro nunca é recusado: se a fatura já não deve tanto (pagou de outro jeito, ou o saldo foi
 * para a seguinte), o pagamento vai para onde a dívida está agora, e o que sobrar vira crédito na
 * fatura atual.
 */
public class InvoiceCharges {

    static final long PIX_VALID_HOURS = 24;
    /** Agência fictícia no campo livre: separa os boletos de fatura dos que os clientes emitem. */
    private static final String CARD_BILLING_BRANCH = "9999";
    private static final String MERCHANT_NAME = "KAGE";
    private static final String MERCHANT_CITY = "SAO PAULO";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", Locale.ROOT);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    /**
     * @param status        PENDING, PAID, EXPIRED (passou do prazo sem pagar) ou CANCELLED
     * @param barcode       boleto: 44 dígitos; nulo no PIX
     * @param digitableLine boleto: a linha digitável formatada; nulo no PIX
     * @param pixCopyPaste  PIX: o copia e cola (vira o QR Code); nulo no boleto
     * @param reference     PIX: o txid; boleto: o nosso número
     */
    public record ChargeView(UUID id, UUID cardId, String referenceMonth, String method, BigDecimal amount,
                             String status, LocalDateTime expiresAt, String barcode, String digitableLine,
                             String pixCopyPaste, String reference, LocalDateTime createdAt, LocalDateTime paidAt,
                             String note) {}

    /**
     * @param appliedMonth  fatura que recebeu o pagamento; nula se nenhuma devia mais nada
     * @param creditAmount  o que sobrou e virou crédito na fatura {@code creditMonth}
     */
    public record Settlement(UUID chargeId, String status, BigDecimal amount, LocalDateTime paidAt, String appliedMonth,
                             BigDecimal appliedAmount, String invoiceStatus, BigDecimal invoiceRemaining,
                             BigDecimal creditAmount, String creditMonth, BigDecimal availableLimit) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceChargeRepository charges;
    private final PayInvoice payInvoice;
    private final String bankCode;
    private final String pixKey;

    public InvoiceCharges(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                          InvoiceChargeRepository charges, PayInvoice payInvoice, String bankCode, String pixKey) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.charges = charges;
        this.payInvoice = payInvoice;
        this.bankCode = bankCode;
        this.pixKey = pixKey;
    }

    /** @param amount nulo = o saldo devedor inteiro */
    @Transactional
    public ChargeView issue(UUID cardId, String referenceMonth, String method, BigDecimal amount) {
        InvoiceCharge.Method chosen = parseMethod(method);
        Card card = findCard(cardId);
        Invoice invoice = findInvoice(card, referenceMonth);
        if (invoice.getStatus() != InvoiceStatus.CLOSED || invoice.remaining().isZero()) {
            throw new BusinessRuleException("Só fatura fechada com saldo devedor pode gerar boleto ou PIX");
        }
        Money value = amount == null ? invoice.remaining() : Money.of(amount);
        if (value.isZero() || value.amount().signum() < 0) throw new ValidationException("Valor deve ser maior que zero");
        if (value.isGreaterThan(invoice.remaining())) {
            throw new BusinessRuleException("Valor maior que o saldo devedor da fatura (" + invoice.remaining().amount() + ")");
        }

        LocalDateTime now = DomainTime.now();
        for (InvoiceCharge previous : charges.findByInvoiceId(invoice.getId())) {
            if (previous.getMethod() == chosen && previous.isPending(now)) {
                previous.cancel(); // só um código de cada forma vale por vez
                charges.update(previous, InvoiceCharge.Status.PENDING);
            }
        }

        UUID id = UUID.randomUUID();
        InvoiceCharge charge = chosen == InvoiceCharge.Method.BOLETO
                ? boleto(id, card, invoice, value, now)
                : pix(id, card, invoice, value, now);
        charges.insert(charge);
        return view(charge, now);
    }

    public List<ChargeView> list(UUID cardId, String referenceMonth) {
        Invoice invoice = findInvoice(findCard(cardId), referenceMonth);
        LocalDateTime now = DomainTime.now();
        return charges.findByInvoiceId(invoice.getId()).stream().map(c -> view(c, now)).toList();
    }

    @Transactional
    public ChargeView cancel(UUID cardId, UUID chargeId) {
        InvoiceCharge charge = findCharge(chargeId);
        if (!charge.getCardId().equals(cardId)) throw new NotFoundException("Cobrança não encontrada");
        charge.cancel();
        if (!charges.update(charge, InvoiceCharge.Status.PENDING)) {
            throw new BusinessRuleException("A cobrança mudou enquanto era cancelada: consulte de novo");
        }
        return view(charge, DomainTime.now());
    }

    /**
     * O dinheiro chegou (backoffice: simula a compensação do boleto ou a liquidação do PIX).
     *
     * @param paidAt quando o cliente pagou no outro banco; nulo = agora
     */
    @Transactional
    public Settlement settle(UUID chargeId, LocalDateTime paidAt) {
        InvoiceCharge charge = findCharge(chargeId);
        LocalDateTime now = DomainTime.now();
        LocalDateTime when = paidAt == null ? now : paidAt;
        charge.settle(when, now);
        if (!charges.update(charge, InvoiceCharge.Status.PENDING)) {
            throw new BusinessRuleException("Cobrança já processada");
        }

        Card card = findCard(charge.getCardId());
        List<Invoice> invoices = invoiceRepository.findByCardId(card.getId());
        Invoice target = debtNow(invoices, charge.getInvoiceId());
        Money toInvoice = target == null ? Money.ZERO
                : charge.getAmount().isGreaterThan(target.remaining()) ? target.remaining() : charge.getAmount();

        PayInvoice.Output paid = null;
        if (!toInvoice.isZero()) paid = payInvoice.receive(card.getId(), target.getId(), toInvoice, when);

        Money leftover = charge.getAmount().subtract(toInvoice);
        String creditMonth = null;
        if (!leftover.isZero()) {
            Invoice current = currentOpen(findCard(card.getId()), invoiceRepository.findByCardId(card.getId()));
            current.addCredit(InvoiceItemKind.CREDIT_BALANCE, charge.getId(), "Pagamento acima do saldo devedor ("
                    + charge.getMethod().name() + " da fatura de " + YearMonth.parse(charge.getReferenceMonth()).format(MONTH)
                    + ")", leftover, now);
            invoiceRepository.save(current);
            creditMonth = current.getReferenceMonth().toString();
        }

        Card after = findCard(card.getId());
        return new Settlement(charge.getId(), charge.getStatus().name(), charge.getAmount().amount(), when,
                target == null || toInvoice.isZero() ? null : target.getReferenceMonth().toString(), toInvoice.amount(),
                paid == null ? null : paid.status(), paid == null ? null : paid.remaining(), leftover.amount(),
                creditMonth, after.availableLimit().amount());
    }

    /**
     * Onde a dívida está agora: a própria fatura, se ainda deve; senão (o saldo foi para a seguinte no
     * rotativo, ou foi parcelado), a fatura fechada mais antiga que ainda tem saldo.
     */
    private static Invoice debtNow(List<Invoice> invoices, UUID invoiceId) {
        Invoice own = invoices.stream().filter(i -> i.getId().equals(invoiceId)).findFirst().orElse(null);
        if (own != null && own.getStatus() == InvoiceStatus.CLOSED && !own.remaining().isZero()) return own;
        return invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.CLOSED && !i.remaining().isZero())
                .min(Comparator.comparing(Invoice::getReferenceMonth))
                .orElse(null);
    }

    /** A fatura aberta mais antiga (a do ciclo atual); criada se o cartão não tiver nenhuma. */
    private Invoice currentOpen(Card card, List<Invoice> invoices) {
        return invoices.stream()
                .filter(i -> i.getStatus() == InvoiceStatus.OPEN)
                .min(Comparator.comparing(Invoice::getReferenceMonth))
                .orElseGet(() -> {
                    YearMonth month = Invoice.referenceMonthFor(DomainTime.today(), card.getClosingDay());
                    while (invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month).isPresent()) {
                        month = month.plusMonths(1);
                    }
                    return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
                });
    }

    private InvoiceCharge boleto(UUID id, Card card, Invoice invoice, Money value, LocalDateTime now) {
        long ourNumber = charges.nextOurNumber();
        LocalDate today = now.toLocalDate();
        // até o vencimento da fatura; se ela já venceu (e ainda não foi para o rotativo), só hoje
        LocalDate due = invoice.getDueDate().isBefore(today) ? today : invoice.getDueDate();
        String free = CARD_BILLING_BRANCH + "0".repeat(8) + "%013d".formatted(ourNumber);
        BoletoCode code = BoletoCode.build(bankCode, due, value.amount(), free);
        return InvoiceCharge.issue(id, card.getId(), invoice.getId(), invoice.getReferenceMonth().toString(),
                InvoiceCharge.Method.BOLETO, value, due.atTime(LocalTime.of(23, 59, 59)), code.barcode(),
                String.valueOf(ourNumber), now);
    }

    private InvoiceCharge pix(UUID id, Card card, Invoice invoice, Money value, LocalDateTime now) {
        String txid = "FAT" + id.toString().replace("-", "").substring(0, 22).toUpperCase(Locale.ROOT);
        String code = PixBrCode.of(pixKey, value.amount(), MERCHANT_NAME, MERCHANT_CITY, txid, true);
        return InvoiceCharge.issue(id, card.getId(), invoice.getId(), invoice.getReferenceMonth().toString(),
                InvoiceCharge.Method.PIX, value, now.plusHours(PIX_VALID_HOURS), code, txid, now);
    }

    static ChargeView view(InvoiceCharge c, LocalDateTime now) {
        String status = c.displayStatus(now);
        boolean boleto = c.getMethod() == InvoiceCharge.Method.BOLETO;
        BoletoCode code = boleto ? BoletoCode.parse(c.getCode()) : null;
        return new ChargeView(c.getId(), c.getCardId(), c.getReferenceMonth(), c.getMethod().name(), c.getAmount().amount(),
                status, c.getExpiresAt(), boleto ? code.barcode() : null, boleto ? code.formattedDigitableLine() : null,
                boleto ? null : c.getCode(), c.getReference(), c.getCreatedAt(), c.getPaidAt(), note(c, status));
    }

    static String note(InvoiceCharge c, String status) {
        return switch (status) {
            case "PAID" -> "Pago em " + c.getPaidAt().format(DATE) + ".";
            case "EXPIRED" -> "Venceu sem pagamento: gere outro código.";
            case "CANCELLED" -> "Cancelado (um código novo substitui o anterior): não pague este.";
            default -> c.getMethod() == InvoiceCharge.Method.BOLETO
                    ? "Pague em qualquer banco até " + c.getExpiresAt().format(DATE) + ". O boleto compensa em até 3 dias "
                    + "úteis: o limite volta quando o pagamento chega, e vale a data em que você pagou."
                    : "Pague pelo app de qualquer banco até " + c.getExpiresAt().format(DATE_TIME) + ". O PIX cai na hora "
                    + "e o limite volta em seguida.";
        };
    }

    private static InvoiceCharge.Method parseMethod(String method) {
        if (method == null) throw new ValidationException("Forma de pagamento é obrigatória (PIX ou BOLETO)");
        try {
            return InvoiceCharge.Method.valueOf(method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Forma de pagamento inválida: use PIX ou BOLETO");
        }
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }

    private Invoice findInvoice(Card card, String referenceMonth) {
        YearMonth month = ReferenceMonths.parse(referenceMonth);
        return invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + month));
    }

    private InvoiceCharge findCharge(UUID chargeId) {
        return charges.findById(chargeId).orElseThrow(() -> new NotFoundException("Cobrança não encontrada"));
    }
}
