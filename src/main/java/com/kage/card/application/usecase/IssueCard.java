package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.service.CardAccountValidationService;
import com.kage.card.domain.service.CardCustomerValidationService;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

public class IssueCard {

    public record Input(UUID customerId, UUID accountId, BigDecimal creditLimit, int closingDay, int dueDay) {}
    public record Output(UUID cardId, String status, BigDecimal creditLimit, BigDecimal availableLimit, int closingDay, int dueDay) {}

    private final CardRepository cardRepository;
    private final CardCustomerValidationService customerValidation;
    private final CardAccountValidationService accountValidation;
    private final CardPlastics plastics;

    public IssueCard(CardRepository cardRepository,
                     CardCustomerValidationService customerValidation,
                     CardAccountValidationService accountValidation) {
        this(cardRepository, customerValidation, accountValidation, null);
    }

    public IssueCard(CardRepository cardRepository,
                     CardCustomerValidationService customerValidation,
                     CardAccountValidationService accountValidation,
                     CardPlastics plastics) {
        this.cardRepository = cardRepository;
        this.customerValidation = customerValidation;
        this.accountValidation = accountValidation;
        this.plastics = plastics;
    }

    /** Emite o cartão e o primeiro plástico, na mesma transação. */
    @Transactional
    public Output execute(Input input) {
        if (input.customerId() == null) {
            throw new ValidationException("Cliente é obrigatório");
        }
        if (input.accountId() == null) {
            throw new ValidationException("Conta é obrigatória");
        }
        if (input.creditLimit() == null || input.creditLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Limite de crédito deve ser maior que zero");
        }

        customerValidation.validateCanIssueCard(input.customerId());
        accountValidation.validateCanLinkCard(input.accountId(), input.customerId());

        Card card = Card.create(input.customerId(), input.accountId(), new Money(input.creditLimit()),
                input.closingDay(), input.dueDay());
        Card saved = cardRepository.save(card);
        if (plastics != null) plastics.issueFirst(saved.getId());

        return new Output(saved.getId(), saved.getStatus().name(), saved.getCreditLimit().amount(),
                saved.availableLimit().amount(), saved.getClosingDay(), saved.getDueDay());
    }
}
