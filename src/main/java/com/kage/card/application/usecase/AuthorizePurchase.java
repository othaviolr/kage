package com.kage.card.application.usecase;

import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;

import java.math.BigDecimal;

/**
 * A compra que chega da bandeira, com o aviso ao cliente nos dois desfechos: aprovada (o
 * RegisterPurchase publica na transação dela) ou recusada por regra de negócio — limite, cartão
 * bloqueado, virtual expirado, cartão novo não desbloqueado. A recusa é avisada aqui, fora da
 * transação da compra, que já sofreu rollback: o evento vai numa transação própria.
 *
 * Deliberadamente sem @Transactional. Entrada malformada (ValidationException) e cartão inexistente
 * não viram aviso: não é algo que o cliente tenha feito ou possa resolver.
 */
public class AuthorizePurchase {

    private final RegisterPurchase registerPurchase;
    private final VirtualCards virtualCards;
    private final CardEventPublisher eventPublisher;

    public AuthorizePurchase(RegisterPurchase registerPurchase, VirtualCards virtualCards, CardEventPublisher eventPublisher) {
        this.registerPurchase = registerPurchase;
        this.virtualCards = virtualCards;
        this.eventPublisher = eventPublisher;
    }

    public RegisterPurchase.Output execute(RegisterPurchase.Input input) {
        try {
            return registerPurchase.execute(input);
        } catch (BusinessRuleException declined) {
            String nickname = input.virtualCardNumber() == null
                    ? null
                    : virtualCards.nicknameOf(input.cardId(), input.virtualCardNumber()).orElse(null);
            BigDecimal amount = input.amount() == null ? BigDecimal.ZERO : input.amount();
            // compra internacional: o valor que chegou está na moeda dela (a conversão pode nem ter acontecido)
            String currency = input.currency() == null || "BRL".equalsIgnoreCase(input.currency().trim())
                    ? null : input.currency().trim().toUpperCase(java.util.Locale.ROOT);
            eventPublisher.publishPurchaseDeclined(input.cardId(), input.description(), Money.of(amount),
                    declined.getMessage(), nickname, currency);
            throw declined;
        }
    }
}
