package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.RevolvingCreditPolicy;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Fecha a fatura de um mês (OPEN -> CLOSED): a partir daí ela não aceita novos lançamentos e passa a
 * poder ser paga. Disparado pelo ciclo de cobrança no dia de corte (ou manualmente pelo backoffice).
 *
 * Antes de fechar, faz a virada do rotativo: o saldo não pago de faturas anteriores entra nesta como
 * "Saldo anterior", com os encargos da RevolvingCreditPolicy (cortados pelo teto legal no Card), e a
 * anterior vira ROLLED_OVER. Tudo na mesma transação do fechamento.
 */
public class CloseInvoice {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record Input(UUID cardId, String referenceMonth) {}
    public record Output(UUID invoiceId, UUID cardId, String referenceMonth, String status,
                         BigDecimal total, LocalDate closingDate, LocalDate dueDate) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CardEventPublisher eventPublisher;

    public CloseInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                        CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Output execute(Input input) {
        YearMonth referenceMonth = ReferenceMonths.parse(input.referenceMonth());

        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(input.cardId(), referenceMonth)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + referenceMonth));

        boolean rolled = false;
        if (invoice.getStatus() == InvoiceStatus.OPEN) {
            for (Invoice previous : invoiceRepository.findByCardId(card.getId())) {
                if (previous.getStatus() == InvoiceStatus.CLOSED && previous.getReferenceMonth().isBefore(referenceMonth)
                        && !previous.remaining().isZero()) {
                    rollInto(previous, invoice, card);
                    rolled = true;
                }
            }
        }

        invoice.close();
        Invoice saved = invoiceRepository.save(invoice);
        if (rolled) cardRepository.save(card);
        eventPublisher.publishInvoiceClosed(saved);

        return new Output(saved.getId(), saved.getCardId(), saved.getReferenceMonth().toString(),
                saved.getStatus().name(), saved.total().amount(), saved.getClosingDate(), saved.getDueDate());
    }

    private void rollInto(Invoice previous, Invoice target, Card card) {
        LocalDateTime now = LocalDateTime.now();
        Money paid = previous.getPaidAmount();
        Money minimum = previous.minimumPayment();
        Money carried = previous.rollOver(now);
        String month = previous.getReferenceMonth().format(MONTH);

        target.addCharge(InvoiceItemKind.PREVIOUS_BALANCE, previous.getId(), "Saldo anterior da fatura de " + month, carried, now);

        RevolvingCreditPolicy.Charges proposed = RevolvingCreditPolicy.chargesFor(carried, paid, minimum,
                previous.getDueDate(), target.getClosingDate());
        Money allowed = card.applyRevolvingCharges(carried, proposed.total());

        // teto legal: o que couber, na ordem juros -> multa -> mora
        Money room = allowed;
        Money interest = take(proposed.revolvingInterest(), room);
        room = room.subtract(interest);
        Money fine = take(proposed.lateFine(), room);
        room = room.subtract(fine);
        Money lateInterest = take(proposed.lateInterest(), room);

        target.addCharge(InvoiceItemKind.REVOLVING_INTEREST, previous.getId(), "Juros do rotativo (fatura de " + month + ")", interest, now);
        target.addCharge(InvoiceItemKind.LATE_FINE, previous.getId(), "Multa por atraso (fatura de " + month + ")", fine, now);
        target.addCharge(InvoiceItemKind.LATE_INTEREST, previous.getId(), "Juros de mora (fatura de " + month + ")", lateInterest, now);

        invoiceRepository.save(previous);
        eventPublisher.publishInvoiceRolledOver(previous, target, carried, interest.add(fine).add(lateInterest), proposed.late());
    }

    private static Money take(Money wanted, Money available) {
        return wanted.isGreaterThan(available) ? available : wanted;
    }
}
