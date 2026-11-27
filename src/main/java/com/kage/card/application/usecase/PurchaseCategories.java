package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.DisputeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.PurchaseCategoryRepository;
import com.kage.card.domain.service.MerchantCategoryPolicy;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Categoria das compras: o resumo de gastos por categoria numa fatura (comparado com a anterior) e a
 * troca da categoria de uma compra pelo cliente.
 *
 * Gasto da fatura = as parcelas que ela cobra (cada parcela no mês dela, como no extrato), mais a compra
 * que voltou numa contestação negada, menos o crédito de contestação — na categoria da compra. Juros,
 * IOF, saldo anterior e parcelamento da fatura não são gasto de categoria nenhuma.
 */
public class PurchaseCategories {

    public record Category(String category, String label) {
        static Category of(PurchaseCategory c) {
            return new Category(c.name(), c.label());
        }
    }

    /**
     * @param share          % do gasto da fatura (1 casa); nulo se a fatura não tem gasto
     * @param previousAmount o gasto da categoria na fatura anterior
     */
    public record Spending(String category, String label, int count, BigDecimal amount, BigDecimal share,
                           BigDecimal previousAmount) {}

    public record SpendingOutput(UUID cardId, String month, BigDecimal total, String previousMonth,
                                 BigDecimal previousTotal, List<Spending> categories) {}

    /** @param mcc nulo = compra sem MCC (ou categoria escolhida antes de ter MCC) */
    public record PurchaseOutput(UUID purchaseId, String mcc, String category, String label, boolean customized) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final PurchaseCategoryRepository categories;
    private final DisputeRepository disputes;

    public PurchaseCategories(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                              PurchaseCategoryRepository categories, DisputeRepository disputes) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.categories = categories;
        this.disputes = disputes;
    }

    public static List<Category> catalog() {
        return Arrays.stream(PurchaseCategory.values()).map(Category::of).toList();
    }

    /** @param month "yyyy-MM"; nulo = a fatura atual (a aberta mais antiga) */
    public SpendingOutput spending(UUID cardId, String month) {
        requireCard(cardId);
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        YearMonth current = month == null ? CardTransactions.currentMonth(invoices) : ReferenceMonths.parse(month);
        YearMonth previous = current.minusMonths(1);
        PurchaseCategoryLookup lookup = PurchaseCategoryLookup.of(cardId, categories, disputes);

        Map<PurchaseCategory, BigDecimal> now = new EnumMap<>(PurchaseCategory.class);
        Map<PurchaseCategory, Integer> counts = new EnumMap<>(PurchaseCategory.class);
        Map<PurchaseCategory, BigDecimal> before = new EnumMap<>(PurchaseCategory.class);
        for (Invoice invoice : invoices) {
            boolean isCurrent = invoice.getReferenceMonth().equals(current);
            if (!isCurrent && !invoice.getReferenceMonth().equals(previous)) continue;
            for (InvoiceItem item : invoice.getItems()) {
                PurchaseCategory category = lookup.categoryOf(item);
                if (category == null) continue;
                BigDecimal amount = item.isCredit() ? item.amount().amount().negate() : item.amount().amount();
                (isCurrent ? now : before).merge(category, amount, BigDecimal::add);
                if (isCurrent && !item.isCredit()) counts.merge(category, 1, Integer::sum);
            }
        }

        BigDecimal total = sum(now);
        List<Spending> list = Arrays.stream(PurchaseCategory.values())
                .filter(c -> now.containsKey(c) || before.containsKey(c))
                .map(c -> {
                    BigDecimal amount = now.getOrDefault(c, BigDecimal.ZERO);
                    BigDecimal share = total.signum() > 0
                            ? amount.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP) : null;
                    return new Spending(c.name(), c.label(), counts.getOrDefault(c, 0), amount, share,
                            before.getOrDefault(c, BigDecimal.ZERO));
                })
                .sorted(Comparator.comparing(Spending::amount).reversed().thenComparing(Spending::label))
                .toList();
        return new SpendingOutput(cardId, current.toString(), total, previous.toString(), sum(before), list);
    }

    public PurchaseOutput get(UUID cardId, UUID purchaseId) {
        requirePurchase(cardId, purchaseId);
        return output(purchaseId, categories.find(cardId, purchaseId).orElse(null));
    }

    /** O cliente escolhe a categoria de uma compra; vale para todas as parcelas dela. */
    public PurchaseOutput customize(UUID cardId, UUID purchaseId, String category) {
        requirePurchase(cardId, purchaseId);
        PurchaseCategory chosen = MerchantCategoryPolicy.parse(category);
        categories.customize(cardId, purchaseId, chosen, DomainTime.now());
        return output(purchaseId, categories.find(cardId, purchaseId).orElseThrow());
    }

    private static PurchaseOutput output(UUID purchaseId, PurchaseCategoryRepository.Entry entry) {
        PurchaseCategory category = entry == null ? PurchaseCategory.OTHER : entry.category();
        return new PurchaseOutput(purchaseId, entry == null ? null : entry.mcc(), category.name(), category.label(),
                entry != null && entry.customized());
    }

    private void requireCard(UUID cardId) {
        cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }

    private void requirePurchase(UUID cardId, UUID purchaseId) {
        requireCard(cardId);
        invoiceRepository.findByCardIdAndPurchaseId(cardId, purchaseId)
                .filter(i -> i.getItems().stream().anyMatch(it -> it.isPurchase() && it.purchaseId().equals(purchaseId)))
                .orElseThrow(() -> new NotFoundException("Compra não encontrada neste cartão"));
    }

    private static BigDecimal sum(Map<PurchaseCategory, BigDecimal> amounts) {
        return amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
