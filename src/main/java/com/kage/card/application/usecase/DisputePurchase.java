package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.DisputeReason;
import com.kage.card.domain.enums.DisputeStatus;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InternationalPurchaseRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.DisputeInterestPolicy;
import com.kage.card.domain.service.InternationalPurchasePolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contestação de compra no cartão (chargeback).
 *
 * Abrir: a compra inteira deixa de ser cobrada enquanto o banco analisa com o estabelecimento — as
 * parcelas que estão em faturas abertas saem delas (e o limite volta na hora), e o que já tinha sido
 * cobrado em fatura fechada vira um crédito na fatura atual (que devolve limite quando abate as
 * cobranças, no fechamento). Se o motivo é "não reconheço", o cartão é bloqueado na hora.
 *
 * Resolver (backoffice/bandeira): procedente, o crédito fica; improcedente, a compra inteira volta a
 * ser cobrada na fatura atual, à vista, com o motivo explicado ao cliente.
 *
 * Prazo: 120 dias da compra, o das bandeiras para chargeback.
 */
public class DisputePurchase {

    public static final int WINDOW_DAYS = 120;

    public record Input(UUID cardId, UUID purchaseId, String reason, String details) {}

    public record Output(UUID disputeId, UUID cardId, UUID purchaseId, String description, BigDecimal amount,
                         BigDecimal creditedAmount, String creditMonth, String reason, String details, String status,
                         boolean cardBlocked, LocalDateTime openedAt, LocalDateTime resolvedAt, String resolutionNote,
                         String reversalMonth, UUID virtualCardId, boolean virtualCardDeleted,
                         BigDecimal interestRefunded) {

        static Output of(Dispute d) {
            return new Output(d.id(), d.cardId(), d.purchaseId(), d.description(), d.amount().amount(),
                    d.creditedAmount().amount(), d.creditMonth(), d.reason().name(), d.details(), d.status().name(),
                    d.cardBlocked(), d.openedAt(), d.resolvedAt(), d.resolutionNote(), d.reversalMonth(),
                    d.virtualCardId(), d.virtualCardDeleted(), d.interestRefunded().amount());
        }
    }

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final DisputeRepository disputeRepository;
    private final CardEventPublisher eventPublisher;
    private final VirtualCards virtualCards;
    private final Cashbacks cashbacks;
    private final InternationalPurchaseRepository internationalPurchases;

    public DisputePurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                           DisputeRepository disputeRepository, CardEventPublisher eventPublisher,
                           VirtualCards virtualCards) {
        this(cardRepository, invoiceRepository, disputeRepository, eventPublisher, virtualCards, null);
    }

    public DisputePurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                           DisputeRepository disputeRepository, CardEventPublisher eventPublisher,
                           VirtualCards virtualCards, Cashbacks cashbacks) {
        this(cardRepository, invoiceRepository, disputeRepository, eventPublisher, virtualCards, cashbacks, null);
    }

    public DisputePurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                           DisputeRepository disputeRepository, CardEventPublisher eventPublisher,
                           VirtualCards virtualCards, Cashbacks cashbacks,
                           InternationalPurchaseRepository internationalPurchases) {
        this.internationalPurchases = internationalPurchases;
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.disputeRepository = disputeRepository;
        this.eventPublisher = eventPublisher;
        this.virtualCards = virtualCards;
        this.cashbacks = cashbacks;
    }

    @Transactional
    public Output open(Input input) {
        if (input.purchaseId() == null) throw new ValidationException("Informe a compra contestada (purchaseId)");
        DisputeReason reason = parseReason(input.reason());
        Dispute.validDetails(reason, input.details());
        Card card = findCard(input.cardId());
        if (card.getStatus() == CardStatus.CANCELLED) {
            throw new BusinessRuleException("Cartão cancelado: a contestação é feita pelo atendimento");
        }
        if (disputeRepository.findByCardIdAndPurchaseId(card.getId(), input.purchaseId()).isPresent()) {
            throw new ConflictException("Esta compra já foi contestada");
        }

        List<Invoice> invoices = new ArrayList<>(invoiceRepository.findByCardId(card.getId()));
        List<Line> lines = new ArrayList<>();
        for (Invoice invoice : invoices) {
            invoice.getItems().stream()
                    .filter(item -> item.purchaseId().equals(input.purchaseId()) && item.isPurchase())
                    .forEach(item -> lines.add(new Line(invoice, item)));
        }
        if (lines.isEmpty()) throw new NotFoundException("Compra não encontrada neste cartão");

        InvoiceItem sample = lines.get(0).item();
        if (lines.size() < sample.installmentCount()) {
            // parte das parcelas foi antecipada e virou outra linha: a conta não fecha sozinha
            throw new BusinessRuleException("Compra com parcelas antecipadas: a contestação é feita pelo atendimento");
        }
        LocalDateTime now = DomainTime.now();
        if (sample.purchasedAt().plusDays(WINDOW_DAYS).isBefore(now)) {
            throw new BusinessRuleException("Prazo de contestação encerrado: são " + WINDOW_DAYS + " dias a partir da compra");
        }

        // 1. parcelas em fatura aberta saem dela: a cobrança fica suspensa e o limite volta já
        Money removed = Money.ZERO;
        Money billed = Money.ZERO;
        Set<Invoice> touched = new HashSet<>();
        for (Line line : lines) {
            if (line.invoice().getStatus() == InvoiceStatus.OPEN) {
                line.invoice().removePurchase(input.purchaseId());
                removed = removed.add(line.item().amount());
                touched.add(line.invoice());
            } else {
                billed = billed.add(line.item().amount());
            }
        }
        Money amount = removed.add(billed);
        String description = sample.description().replaceFirst("\\s*\\(\\d+/\\d+\\)$", "");

        // compra internacional: o IOF dela é suspenso junto (sai da fatura aberta ou vira crédito próprio)
        UUID iofLine = InvoiceItem.chargeId(InvoiceItemKind.IOF, input.purchaseId());
        Money iofRemoved = Money.ZERO;
        Money iofBilled = Money.ZERO;
        for (Invoice invoice : invoices) {
            InvoiceItem iof = invoice.getItems().stream()
                    .filter(i -> i.kind() == InvoiceItemKind.IOF && i.purchaseId().equals(iofLine))
                    .findFirst().orElse(null);
            if (iof == null) continue;
            if (invoice.getStatus() == InvoiceStatus.OPEN) {
                invoice.removeCharge(iofLine);
                iofRemoved = iofRemoved.add(iof.amount());
                touched.add(invoice);
            } else {
                iofBilled = iofBilled.add(iof.amount());
            }
        }
        removed = removed.add(iofRemoved); // o limite volta com o IOF

        // 2. o que já foi cobrado (fatura fechada, paga ou levada adiante) volta como crédito na atual
        Invoice creditInvoice = null;
        if (!billed.isZero()) {
            UUID disputeKey = creditKey(card.getId(), input.purchaseId());
            creditInvoice = currentInvoice(card, invoices);
            creditInvoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, disputeKey,
                    "Crédito de contestação: " + description, billed, now);
            touched.add(creditInvoice);
        }
        if (!iofBilled.isZero()) {
            if (creditInvoice == null) creditInvoice = currentInvoice(card, invoices);
            creditInvoice.addCredit(InvoiceItemKind.DISPUTE_CREDIT, iofCreditKey(card.getId(), input.purchaseId()),
                    "Crédito de contestação: IOF de " + description, iofBilled, now);
            touched.add(creditInvoice);
        }

        for (Invoice invoice : touched) {
            if (invoice.isEmpty() && invoice.getVersion() != null) {
                invoiceRepository.delete(invoice); // fatura futura que só tinha esta compra
            } else if (!invoice.isEmpty()) {
                invoiceRepository.save(invoice);
            }
        }

        if (!removed.isZero()) card.releaseLimit(removed);
        boolean blocked = false;
        boolean virtualDeleted = false;
        UUID virtualCardId = sample.virtualCardId();
        if (reason.isFraud()) {
            if (virtualCardId != null && virtualCards != null) {
                // o número que vazou é o do virtual: apagá-lo basta, o físico segue funcionando
                virtualDeleted = virtualCards.deleteCompromised(virtualCardId);
            } else if (card.getStatus() == CardStatus.ACTIVE) {
                card.block(); // "não reconheço": ninguém mais compra com este cartão até o cliente desbloquear
                blocked = true;
            }
        }
        cardRepository.save(card);

        Dispute dispute = Dispute.open(card.getId(), input.purchaseId(), description, amount, billed,
                creditInvoice == null ? null : creditInvoice.getReferenceMonth().toString(), reason, input.details(),
                blocked, virtualCardId, virtualDeleted);
        try {
            disputeRepository.insert(dispute);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Esta compra já foi contestada");
        }
        eventPublisher.publishDisputeOpened(dispute);
        return Output.of(dispute);
    }

    public List<Output> list(UUID cardId) {
        findCard(cardId);
        return disputeRepository.findByCardId(cardId).stream().map(Output::of).toList();
    }

    public Output get(UUID cardId, UUID disputeId) {
        return Output.of(find(cardId, disputeId));
    }

    /** Parecer do banco/bandeira. {@code accepted} = procedente. */
    @Transactional
    public Output resolve(UUID cardId, UUID disputeId, Boolean accepted, String note) {
        if (accepted == null) throw new ValidationException("Informe se a contestação é procedente (accepted)");
        Dispute dispute = find(cardId, disputeId);
        if (dispute.status() != DisputeStatus.OPEN) throw new BusinessRuleException("Contestação já foi resolvida");

        Dispute resolved;
        if (accepted) {
            resolved = acceptRefundingCharges(dispute, note);
        } else {
            Card card = findCard(cardId);
            List<Invoice> invoices = new ArrayList<>(invoiceRepository.findByCardId(cardId));
            Invoice current = currentInvoice(card, invoices);
            resolved = dispute.reject(note, current.getReferenceMonth().toString());
            current.addCharge(InvoiceItemKind.DISPUTE_REVERSAL, dispute.id(),
                    "Contestação negada: " + dispute.description(), dispute.amount(), DomainTime.now());
            // compra internacional: o IOF, suspenso na abertura, volta a ser cobrado
            Money iof = internationalPurchases == null ? Money.ZERO : internationalPurchases.find(dispute.purchaseId())
                    .map(q -> Money.of(q.iof())).orElse(Money.ZERO);
            current.addCharge(InvoiceItemKind.IOF, dispute.id(), "IOF da compra internacional ("
                    + InternationalPurchasePolicy.IOF_PERCENT.replace('.', ',') + "%): " + dispute.description()
                    + " (contestação negada)", iof, DomainTime.now());
            invoiceRepository.save(current);
            card.reinstateCharge(dispute.amount().add(iof));
            cardRepository.save(card);
        }
        if (!disputeRepository.resolve(resolved)) throw new ConflictException("Contestação já foi resolvida");
        eventPublisher.publishDisputeResolved(resolved);
        return Output.of(resolved);
    }

    /**
     * Procedente: além do crédito da compra (que já entrou na abertura), devolve os juros e encargos
     * que ela gerou enquanto estava cobrada — numa linha de crédito na fatura atual.
     */
    private Dispute acceptRefundingCharges(Dispute dispute, String note) {
        Card card = findCard(dispute.cardId());
        List<Invoice> invoices = new ArrayList<>(invoiceRepository.findByCardId(card.getId()));
        DisputeInterestPolicy.Refund refund = DisputeInterestPolicy.refundFor(invoices, dispute.purchaseId());
        Dispute accepted = dispute.accept(note, refund.total()); // valida o parecer antes de mexer na fatura
        Invoice current = currentInvoice(card, invoices);
        boolean changed = false;
        if (!refund.total().isZero()) {
            current.addCredit(InvoiceItemKind.DISPUTE_INTEREST_REFUND, dispute.id(),
                    "Estorno de juros, encargos e IOF: " + dispute.description(), refund.total(), DomainTime.now());
            card.refundRevolvingCharges(refund.charges()); // o IOF não estava no teto
            changed = true;
        }
        // a compra não valeu: o cashback que ela já tinha rendido volta
        if (cashbacks != null && !cashbacks.reverseFor(card, dispute, invoices, current).isZero()) changed = true;
        if (changed) {
            invoiceRepository.save(current);
            cardRepository.save(card);
        }
        return accepted;
    }

    private record Line(Invoice invoice, InvoiceItem item) {}

    /**
     * A fatura que recebe os lançamentos de hoje: a do mês em que uma compra feita agora cairia e, se
     * ela já fechou, a primeira aberta depois dela (criada se não existir).
     */
    private static Invoice currentInvoice(Card card, List<Invoice> invoices) {
        LocalDate today = DomainTime.today();
        YearMonth month = Invoice.referenceMonthFor(today, card.getClosingDay());
        // troca de vencimento: a fatura do mês anterior pode seguir aberta com o fechamento antigo, mais tarde
        YearMonth before = month.minusMonths(1);
        Invoice previous = invoices.stream().filter(i -> i.getReferenceMonth().equals(before)).findFirst().orElse(null);
        if (previous != null && previous.acceptsPurchaseOn(today)) return previous;
        while (true) {
            YearMonth wanted = month;
            Invoice existing = invoices.stream().filter(i -> i.getReferenceMonth().equals(wanted)).findFirst().orElse(null);
            if (existing == null) {
                Invoice created = Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
                invoices.add(created);
                return created;
            }
            if (existing.getStatus() == InvoiceStatus.OPEN) return existing;
            month = month.plusMonths(1);
        }
    }

    /** Id estável do crédito: a mesma compra não gera dois créditos no mesmo cartão. */
    static UUID creditKey(UUID cardId, UUID purchaseId) {
        return UUID.nameUUIDFromBytes(("DISPUTE:" + cardId + ":" + purchaseId).getBytes());
    }

    /** Crédito do IOF da compra internacional contestada: uma linha própria, ao lado da compra. */
    private static UUID iofCreditKey(UUID cardId, UUID purchaseId) {
        return UUID.nameUUIDFromBytes(("DISPUTE_IOF:" + cardId + ":" + purchaseId).getBytes());
    }

    private static DisputeReason parseReason(String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("Informe o motivo da contestação (reason)");
        try {
            return DisputeReason.valueOf(reason.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Motivo inválido: use NOT_RECOGNIZED, DUPLICATE_CHARGE, NOT_RECEIVED ou CANCELLED_PURCHASE");
        }
    }

    private Dispute find(UUID cardId, UUID disputeId) {
        findCard(cardId);
        return disputeRepository.findById(disputeId)
                .filter(d -> d.cardId().equals(cardId))
                .orElseThrow(() -> new NotFoundException("Contestação não encontrada"));
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
