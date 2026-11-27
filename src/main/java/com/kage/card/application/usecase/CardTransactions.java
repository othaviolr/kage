package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.CardPlastic;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.entity.VirtualCard;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.repository.CardPlasticRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.VirtualCardRepository;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Extrato do cartão: os lançamentos das faturas de um período, para consultar, filtrar e exportar
 * (CSV para planilha, OFX para app de finanças). O período é por mês de fatura — cada parcela aparece
 * no mês em que é cobrada, uma vez só.
 *
 * Valor positivo é cobrança; negativo, crédito (como na fatura). O saldo anterior e o parcelamento de
 * fatura são dívida que já apareceu em outro mês (com os juros dela): ficam no grupo
 * {@link Group#BALANCES}, que dá para tirar com o filtro, para quem soma gastos e não quer contar duas vezes.
 */
public class CardTransactions {

    /** Até 12 faturas por consulta: um ano. */
    static final int MAX_MONTHS = 12;
    /** Sem período: as últimas 6 faturas, até a atual. */
    static final int DEFAULT_MONTHS = 6;

    public enum Group {
        /** Compras (e compra que voltou a ser cobrada numa contestação negada). */
        PURCHASES("Compras"),
        /** Juros, multa, mora e IOF. */
        FEES("Juros, encargos e IOF"),
        /** Créditos: contestação, cashback, saldo credor. */
        CREDITS("Créditos"),
        /** Saldo anterior, parcelas do parcelamento de fatura, antecipação, estorno de cashback. */
        BALANCES("Saldos e parcelamentos");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Group of(InvoiceItemKind kind) {
            return switch (kind) {
                case PURCHASE, DISPUTE_REVERSAL -> PURCHASES;
                case REVOLVING_INTEREST, LATE_FINE, LATE_INTEREST, IOF -> FEES;
                case DISPUTE_CREDIT, CREDIT_BALANCE, DISPUTE_INTEREST_REFUND, CASHBACK -> CREDITS;
                case PREVIOUS_BALANCE, INVOICE_INSTALLMENT, ANTICIPATION, CASHBACK_REVERSAL -> BALANCES;
            };
        }
    }

    /**
     * @param amount         cobrança positiva, crédito negativo
     * @param installment    "2/10" na compra parcelada; nulo à vista
     * @param virtualCard    apelido do cartão virtual da compra; nulo = físico (ou não é compra)
     */
    public record Line(UUID id, LocalDateTime date, String invoiceMonth, String description, BigDecimal amount,
                       String kind, String group, String installment, UUID virtualCardId, String virtualCard) {}

    public record Total(String group, String label, int count, BigDecimal amount) {}

    /**
     * @param cardLastDigits últimos dígitos do cartão físico (o OFX identifica a conta por eles)
     * @param groups         grupos incluídos (o filtro); todos, sem filtro
     */
    public record Output(UUID cardId, String cardLastDigits, String fromMonth, String toMonth, List<String> groups,
                         UUID virtualCardId, List<Line> lines, List<Total> totals, BigDecimal total,
                         LocalDateTime generatedAt) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final VirtualCardRepository virtualCards;
    private final CardPlasticRepository plastics;

    public CardTransactions(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                            VirtualCardRepository virtualCards, CardPlasticRepository plastics) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.virtualCards = virtualCards;
        this.plastics = plastics;
    }

    /**
     * @param groups        "PURCHASES,FEES"; nulo = todos
     * @param virtualCardId só as compras feitas com este cartão virtual
     */
    public Output list(UUID cardId, String fromMonth, String toMonth, String groups, UUID virtualCardId) {
        Card card = cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        Map<UUID, String> nicknames = virtualCards.findByCardId(cardId).stream()
                .collect(Collectors.toMap(VirtualCard::id, VirtualCard::nickname));
        if (virtualCardId != null && !nicknames.containsKey(virtualCardId)) {
            throw new NotFoundException("Cartão virtual não encontrado neste cartão");
        }

        YearMonth to = toMonth == null ? currentMonth(invoices) : ReferenceMonths.parse(toMonth);
        YearMonth from = fromMonth == null ? to.minusMonths(DEFAULT_MONTHS - 1) : ReferenceMonths.parse(fromMonth);
        if (from.isAfter(to)) throw new ValidationException("Mês inicial depois do final");
        if (from.plusMonths(MAX_MONTHS - 1).isBefore(to)) {
            throw new ValidationException("Período de até " + MAX_MONTHS + " faturas");
        }
        List<Group> chosen = parseGroups(groups);

        List<Line> lines = invoices.stream()
                .filter(i -> !i.getReferenceMonth().isBefore(from) && !i.getReferenceMonth().isAfter(to))
                .flatMap(i -> i.getItems().stream().map(item -> line(i, item, nicknames)))
                .filter(l -> chosen.contains(Group.valueOf(l.group())))
                .filter(l -> virtualCardId == null || virtualCardId.equals(l.virtualCardId()))
                .sorted(Comparator.comparing(Line::date).thenComparing(Line::invoiceMonth).thenComparing(Line::description))
                .toList();

        List<Total> totals = chosen.stream().map(g -> {
            List<Line> of = lines.stream().filter(l -> l.group().equals(g.name())).toList();
            return new Total(g.name(), g.label(), of.size(), sum(of));
        }).toList();

        String lastDigits = plastics.findByCardId(cardId).stream().filter(CardPlastic::isActive).findFirst()
                .or(() -> plastics.findByCardId(cardId).stream().findFirst())
                .map(CardPlastic::lastDigits).orElse(null);
        return new Output(card.getId(), lastDigits, from.toString(), to.toString(), chosen.stream().map(Enum::name).toList(),
                virtualCardId, lines, totals, sum(lines), DomainTime.now());
    }

    private static Line line(Invoice invoice, InvoiceItem item, Map<UUID, String> nicknames) {
        BigDecimal amount = item.isCredit() ? item.amount().amount().negate() : item.amount().amount();
        String installment = item.installmentCount() != null && item.installmentCount() > 1
                ? item.installmentNumber() + "/" + item.installmentCount() : null;
        return new Line(item.id(), item.purchasedAt(), invoice.getReferenceMonth().toString(), item.description(), amount,
                item.kind().name(), Group.of(item.kind()).name(), installment, item.virtualCardId(),
                item.virtualCardId() == null ? null : nicknames.get(item.virtualCardId()));
    }

    /** A fatura atual (a aberta mais antiga) ou, se não há aberta, a mais recente. */
    private static YearMonth currentMonth(List<Invoice> invoices) {
        return invoices.stream()
                .filter(i -> i.getStatus() == com.kage.card.domain.enums.InvoiceStatus.OPEN)
                .map(Invoice::getReferenceMonth).min(Comparator.naturalOrder())
                .or(() -> invoices.stream().map(Invoice::getReferenceMonth).max(Comparator.naturalOrder()))
                .orElse(YearMonth.from(DomainTime.today()));
    }

    private static List<Group> parseGroups(String raw) {
        if (raw == null || raw.isBlank()) return List.of(Group.values());
        try {
            return Arrays.stream(raw.split(",")).map(s -> Group.valueOf(s.trim().toUpperCase(Locale.ROOT)))
                    .distinct().sorted().toList();
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Tipo inválido: use PURCHASES, FEES, CREDITS ou BALANCES");
        }
    }

    private static BigDecimal sum(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
