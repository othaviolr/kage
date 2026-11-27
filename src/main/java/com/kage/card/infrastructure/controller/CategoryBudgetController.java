package com.kage.card.infrastructure.controller;

import com.kage.auth.infrastructure.security.AccessGuard;
import com.kage.card.application.usecase.CategoryBudgets;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** Orçamento mensal por categoria de compra: quanto já foi gasto de cada um na fatura, e o aviso em 80%/100%. */
@RestController
@RequestMapping("/api/cards/{id}/budgets")
public class CategoryBudgetController {

    private final CategoryBudgets categoryBudgets;
    private final AccessGuard accessGuard;

    public CategoryBudgetController(CategoryBudgets categoryBudgets, AccessGuard accessGuard) {
        this.categoryBudgets = categoryBudgets;
        this.accessGuard = accessGuard;
    }

    /** @param month "yyyy-MM"; sem ele, a fatura atual */
    @GetMapping
    public ResponseEntity<CategoryBudgets.Output> list(@PathVariable UUID id, @RequestParam(required = false) String month) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(categoryBudgets.list(id, month));
    }

    @PutMapping("/{category}")
    public ResponseEntity<CategoryBudgets.Item> set(@PathVariable UUID id, @PathVariable String category,
                                                    @RequestBody BudgetRequest request) {
        accessGuard.checkCard(id);
        return ResponseEntity.ok(categoryBudgets.set(id, category, request.amount()));
    }

    @DeleteMapping("/{category}")
    public ResponseEntity<Void> remove(@PathVariable UUID id, @PathVariable String category) {
        accessGuard.checkCard(id);
        categoryBudgets.remove(id, category);
        return ResponseEntity.noContent().build();
    }

    public record BudgetRequest(BigDecimal amount) {
    }
}
