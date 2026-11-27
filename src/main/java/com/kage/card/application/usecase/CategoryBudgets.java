package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.CategoryBudgetRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.PurchaseCategoryRepository;
import com.kage.card.domain.service.LimitUsagePolicy;
import com.kage.card.domain.service.MerchantCategoryPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orçamento mensal por categoria: quanto o cliente quer gastar em cada fatura com mercado, restaurantes
 * etc. O gasto é o do resumo por categoria ({@link PurchaseCategories#spending}): cada parcela no mês
 * dela, crédito de contestação abatendo. Ao chegar em 80% e em 100% do orçamento, um aviso — cada faixa
 * uma vez por categoria e fatura, como o alerta de uso do limite ({@link LimitUsageAlerts}); se a compra
 * pula direto para 100%, só esse sai.
 */
public class CategoryBudgets {

    /** Teto do orçamento de uma categoria: evita valor digitado errado (e não há limite de cartão maior). */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

    public enum Status {
        /** Abaixo de 80%. */
        OK,
        /** De 80% até antes de 100%. */
        WARNING,
        /** Chegou ou passou do orçamento. */
        EXCEEDED
    }

    /**
     * @param usagePercent gasto sobre o orçamento, arredondado para baixo (0 se o gasto é zero ou negativo)
     * @param remaining    quanto ainda cabe (negativo = passou do orçamento)
     */
    public record Item(String category, String label, BigDecimal budget, BigDecimal spent, BigDecimal remaining,
                       int usagePercent, String status) {}

    public record Output(UUID cardId, String month, List<Item> budgets, BigDecimal totalBudget, BigDecimal totalSpent) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CategoryBudgetRepository budgets;
    private final PurchaseCategoryRepository categories;
    private final PurchaseCategories purchaseCategories;
    private final CardEventPublisher eventPublisher;

    public CategoryBudgets(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                           CategoryBudgetRepository budgets, PurchaseCategoryRepository categories,
                           PurchaseCategories purchaseCategories, CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.budgets = budgets;
        this.categories = categories;
        this.purchaseCategories = purchaseCategories;
        this.eventPublisher = eventPublisher;
    }

    /** @param month "yyyy-MM"; nulo = a fatura atual */
    public Output list(UUID cardId, String month) {
        requireCard(cardId);
        String referenceMonth = month == null ? currentMonth(cardId).toString() : ReferenceMonths.parse(month).toString();
        Map<String, BigDecimal> spent = spentByCategory(cardId, referenceMonth);
        List<Item> items = budgets.findByCardId(cardId).stream()
                .sorted(Comparator.comparing(b -> b.category().ordinal()))
                .map(b -> item(b, spent.getOrDefault(b.category().name(), BigDecimal.ZERO)))
                .toList();
        return new Output(cardId, referenceMonth, items, items.stream().map(Item::budget).reduce(BigDecimal.ZERO, BigDecimal::add),
                items.stream().map(Item::spent).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Cria ou troca o orçamento da categoria (vale para todas as faturas, a partir da atual). */
    public Item set(UUID cardId, String category, BigDecimal amount) {
        requireCard(cardId);
        PurchaseCategory chosen = MerchantCategoryPolicy.parse(category);
        if (amount == null || amount.signum() <= 0) throw new ValidationException("Orçamento deve ser maior que zero");
        if (amount.scale() > 2) throw new ValidationException("Orçamento com no máximo 2 casas decimais");
        if (amount.compareTo(MAX_AMOUNT) > 0) throw new ValidationException("Orçamento de até R$ 1.000.000,00 por categoria");
        budgets.save(cardId, chosen, Money.of(amount), DomainTime.now());
        String month = currentMonth(cardId).toString();
        return item(budgets.find(cardId, chosen).orElseThrow(),
                spentByCategory(cardId, month).getOrDefault(chosen.name(), BigDecimal.ZERO));
    }

    public void remove(UUID cardId, String category) {
        requireCard(cardId);
        if (!budgets.delete(cardId, MerchantCategoryPolicy.parse(category))) {
            throw new NotFoundException("Sem orçamento para essa categoria");
        }
    }

    /**
     * Depois de uma compra aprovada: se a categoria dela tem orçamento, avisa a faixa nova atingida na
     * fatura {@code referenceMonth} (a da compra ou da 1ª parcela). Chamar dentro da transação da compra.
     */
    public void afterPurchase(UUID cardId, UUID purchaseId, YearMonth referenceMonth) {
        PurchaseCategory category = categories.find(cardId, purchaseId)
                .map(PurchaseCategoryRepository.Entry::category).orElse(PurchaseCategory.OTHER);
        CategoryBudgetRepository.Budget budget = budgets.find(cardId, category).orElse(null);
        if (budget == null) return;
        BigDecimal spent = spentByCategory(cardId, referenceMonth.toString()).getOrDefault(category.name(), BigDecimal.ZERO);
        int percent = usagePercent(spent, budget.amount().amount());
        Integer toAlert = null;
        for (int threshold : List.of(LimitUsagePolicy.WARNING, LimitUsagePolicy.EXHAUSTED)) {
            if (percent >= threshold && budgets.markAlerted(cardId, category, referenceMonth, threshold, DomainTime.now())) {
                toAlert = threshold; // a maior faixa nova
            }
        }
        if (toAlert == null) return;
        eventPublisher.publishCategoryBudgetAlert(cardId, referenceMonth.toString(), category.name(), category.label(),
                toAlert, percent, budget.amount(), spent);
    }

    private Item item(CategoryBudgetRepository.Budget budget, BigDecimal spent) {
        BigDecimal amount = budget.amount().amount();
        int percent = usagePercent(spent, amount);
        Status status = percent >= LimitUsagePolicy.EXHAUSTED ? Status.EXCEEDED
                : percent >= LimitUsagePolicy.WARNING ? Status.WARNING : Status.OK;
        return new Item(budget.category().name(), budget.category().label(), amount, spent, amount.subtract(spent),
                percent, status.name());
    }

    /** Arredondado para baixo: R$ 799,99 de R$ 1.000 é 79%, não 80%. */
    static int usagePercent(BigDecimal spent, BigDecimal budget) {
        if (spent.signum() <= 0) return 0;
        return spent.multiply(BigDecimal.valueOf(100)).divide(budget, 0, RoundingMode.DOWN).intValue();
    }

    private Map<String, BigDecimal> spentByCategory(UUID cardId, String month) {
        return purchaseCategories.spending(cardId, month).categories().stream()
                .collect(Collectors.toMap(PurchaseCategories.Spending::category, PurchaseCategories.Spending::amount));
    }

    private YearMonth currentMonth(UUID cardId) {
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        return CardTransactions.currentMonth(invoices);
    }

    private void requireCard(UUID cardId) {
        cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
