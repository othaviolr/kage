package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Dispute;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.CashbackDestination;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.CashbackAccountCreditRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.card.domain.service.CashbackPolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cashback do cartão: quando a fatura é paga inteira até o vencimento, vai para onde o cliente
 * escolheu — abatendo a fatura seguinte (linha de crédito: abate o total e devolve limite no
 * fechamento; é o padrão) ou como dinheiro na conta (lançamento no extrato). Conta que não está ativa
 * não recebe: o cashback vai para a fatura. Se uma compra que rendeu cashback for contestada com
 * sucesso depois, a parte dela volta a ser cobrada na fatura atual, seja qual for o destino.
 *
 * Idempotente: o crédito de uma fatura e o estorno de uma contestação têm id determinístico; o da
 * conta, a fatura de origem como chave.
 */
public class Cashbacks {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    /** @param creditedIn mês da fatura que recebeu o crédito; nulo quando foi para a conta */
    public record InvoiceCashback(String referenceMonth, BigDecimal amount, String destination, String creditedIn,
                                  LocalDateTime creditedAt) {}

    /** @param destination para onde vão os próximos */
    public record Summary(UUID cardId, BigDecimal rate, String destination, BigDecimal earned, BigDecimal toInvoices,
                          BigDecimal toAccount, BigDecimal reversed, BigDecimal net, List<InvoiceCashback> invoices) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final DisputeRepository disputeRepository;
    private final CardEventPublisher eventPublisher;
    private final CashbackAccountCreditRepository accountCredits;
    private final CardBillingAccountService account;

    public Cashbacks(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                     DisputeRepository disputeRepository, CardEventPublisher eventPublisher,
                     CashbackAccountCreditRepository accountCredits, CardBillingAccountService account) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.disputeRepository = disputeRepository;
        this.eventPublisher = eventPublisher;
        this.accountCredits = accountCredits;
        this.account = account;
    }

    /**
     * Fatura acabou de ser quitada: se foi em dia, credita o cashback no destino escolhido. Chamar dentro
     * da transação do pagamento (a fatura paga ainda em memória).
     *
     * @return o valor creditado (zero se não teve direito)
     */
    public Money creditFor(Card card, Invoice paid) {
        if (!CashbackPolicy.eligible(paid)) return Money.ZERO;
        Set<UUID> disputed = disputeRepository.findByCardId(card.getId()).stream()
                .map(Dispute::purchaseId).collect(Collectors.toSet());
        Money cashback = CashbackPolicy.of(CashbackPolicy.base(paid, disputed));
        if (cashback.isZero()) return Money.ZERO;

        // já creditado (na fatura ou na conta): não credita de novo
        if (creditedAt(invoiceRepository.findByCardId(card.getId()), accountCredits.findByCardId(card.getId()), paid).isPresent()) {
            return Money.ZERO;
        }

        String month = paid.getReferenceMonth().toString();
        if (card.getCashbackDestination() == CashbackDestination.ACCOUNT
                && account.creditCashback(card.getAccountId(), cashback, paid.getId())) {
            accountCredits.insert(new CashbackAccountCreditRepository.Credit(paid.getId(), card.getId(), cashback, DomainTime.now()));
            eventPublisher.publishCashbackEarned(card.getId(), month, cashback, CashbackDestination.ACCOUNT.name());
            return cashback;
        }

        Invoice target = openInvoiceFrom(card, paid.getReferenceMonth().plusMonths(1));
        target.addCredit(InvoiceItemKind.CASHBACK, paid.getId(),
                "Cashback de 1% da fatura de " + paid.getReferenceMonth().format(MONTH), cashback, DomainTime.now());
        invoiceRepository.save(target);
        eventPublisher.publishCashbackEarned(card.getId(), month, cashback, CashbackDestination.INVOICE.name());
        return cashback;
    }

    /**
     * Contestação procedente de uma compra que já rendeu cashback (na fatura ou na conta): a parte dela
     * volta a ser cobrada na fatura atual. Chamar dentro da transação da resolução.
     *
     * @return o valor estornado
     */
    public Money reverseFor(Card card, Dispute dispute, List<Invoice> invoices, Invoice current) {
        List<CashbackAccountCreditRepository.Credit> toAccount = accountCredits.findByCardId(card.getId());
        Money reversal = Money.ZERO;
        for (Invoice invoice : invoices) {
            // só se o cashback daquela fatura saiu ANTES da contestação: se ela já existia, a compra ficou de fora
            Optional<LocalDateTime> creditedAt = creditedAt(invoices, toAccount, invoice);
            if (creditedAt.isEmpty() || !creditedAt.get().isBefore(dispute.openedAt())) continue;
            Money lines = invoice.getItems().stream()
                    .filter(i -> i.isPurchase() && i.purchaseId().equals(dispute.purchaseId()))
                    .map(InvoiceItem::amount).reduce(Money.ZERO, Money::add);
            reversal = reversal.add(CashbackPolicy.of(lines));
        }
        if (reversal.isZero()) return Money.ZERO;
        current.addCharge(InvoiceItemKind.CASHBACK_REVERSAL, dispute.id(),
                "Estorno de cashback: " + dispute.description(), reversal, DomainTime.now());
        card.reinstateCharge(reversal);
        return reversal;
    }

    /** Para onde vão os próximos cashbacks. Os já creditados ficam onde estão. */
    @Transactional
    public Summary chooseDestination(UUID cardId, String destination) {
        Card card = cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        card.chooseCashbackDestination(parse(destination));
        cardRepository.save(card);
        return summary(cardId);
    }

    public Summary summary(UUID cardId) {
        Card card = cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        List<InvoiceCashback> details = new ArrayList<>();
        BigDecimal toInvoices = BigDecimal.ZERO;
        BigDecimal reversed = BigDecimal.ZERO;
        for (Invoice invoice : invoices) {
            for (InvoiceItem item : invoice.getItems()) {
                if (item.kind() == InvoiceItemKind.CASHBACK) {
                    toInvoices = toInvoices.add(item.amount().amount());
                    details.add(new InvoiceCashback(sourceMonth(invoices, item.purchaseId(), true), item.amount().amount(),
                            CashbackDestination.INVOICE.name(), invoice.getReferenceMonth().toString(), item.purchasedAt()));
                } else if (item.kind() == InvoiceItemKind.CASHBACK_REVERSAL) {
                    reversed = reversed.add(item.amount().amount());
                }
            }
        }
        BigDecimal toAccount = BigDecimal.ZERO;
        for (CashbackAccountCreditRepository.Credit credit : accountCredits.findByCardId(cardId)) {
            toAccount = toAccount.add(credit.amount().amount());
            details.add(new InvoiceCashback(sourceMonth(invoices, credit.sourceInvoiceId(), false), credit.amount().amount(),
                    CashbackDestination.ACCOUNT.name(), null, credit.creditedAt()));
        }
        details.sort(Comparator.comparing(InvoiceCashback::referenceMonth, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        BigDecimal earned = toInvoices.add(toAccount);
        return new Summary(cardId, CashbackPolicy.RATE, card.getCashbackDestination().name(), earned, toInvoices,
                toAccount, reversed, earned.subtract(reversed), details);
    }

    private static CashbackDestination parse(String destination) {
        if (destination == null) throw new ValidationException("Destino do cashback é obrigatório (INVOICE ou ACCOUNT)");
        try {
            return CashbackDestination.valueOf(destination.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Destino do cashback inválido: use INVOICE (abater a fatura) ou ACCOUNT (na conta)");
        }
    }

    /** Quando o cashback que a fatura {@code invoice} gerou foi creditado (na fatura seguinte ou na conta), se foi. */
    private static Optional<LocalDateTime> creditedAt(List<Invoice> invoices, List<CashbackAccountCreditRepository.Credit> toAccount,
                                                      Invoice invoice) {
        UUID lineId = InvoiceItem.chargeId(InvoiceItemKind.CASHBACK, invoice.getId());
        Optional<LocalDateTime> onInvoice = invoices.stream().flatMap(i -> i.getItems().stream())
                .filter(item -> item.purchaseId().equals(lineId)).map(InvoiceItem::purchasedAt).findFirst();
        if (onInvoice.isPresent()) return onInvoice;
        return toAccount.stream().filter(c -> c.sourceInvoiceId().equals(invoice.getId()))
                .map(CashbackAccountCreditRepository.Credit::creditedAt).findFirst();
    }

    /**
     * Mês da fatura que gerou este cashback: na fatura, o id da linha deriva da fatura de origem; na
     * conta, o crédito guarda a própria fatura de origem.
     */
    private static String sourceMonth(List<Invoice> invoices, UUID reference, boolean invoiceLine) {
        return invoices.stream()
                .filter(i -> (invoiceLine ? InvoiceItem.chargeId(InvoiceItemKind.CASHBACK, i.getId()) : i.getId()).equals(reference))
                .map(i -> i.getReferenceMonth().toString())
                .findFirst().orElse(null);
    }

    private Invoice openInvoiceFrom(Card card, YearMonth from) {
        YearMonth month = from;
        while (true) {
            Optional<Invoice> existing = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month);
            if (existing.isEmpty()) return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
            if (existing.get().getStatus() == InvoiceStatus.OPEN) return existing.get();
            month = month.plusMonths(1);
        }
    }
}
