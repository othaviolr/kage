package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.DueDayChangeRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.BillingDayPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Troca do dia de vencimento da fatura. O cliente escolhe o vencimento; o fechamento acompanha
 * ({@link BillingDayPolicy}).
 *
 * A fatura atual (a aberta mais antiga) não muda: as compras dela já foram feitas contando com aquele
 * vencimento. A troca vale a partir da seguinte, que é a de <b>transição</b>: vai do fechamento antigo
 * da atual até o fechamento novo, então sai mais curta ou mais longa que um mês. Faturas futuras que
 * já existem (por causa de parcelas) passam para as datas novas. Fatura fechada esperando o
 * vencimento também não muda.
 *
 * Não troca: cartão cancelado, com fatura vencida em aberto (o atraso se resolve pagando, não mudando
 * a data) ou trocado há menos de {@value BillingDayPolicy#DAYS_BETWEEN_CHANGES} dias.
 */
public class DueDays {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    public record Option(int dueDay, int closingDay) {}

    /**
     * @param blockedReason        por que não dá para trocar agora; nulo quando dá
     * @param nextChangeAllowedOn  trocou há pouco: a partir de quando pode trocar de novo; nulo se já pode
     * @param options              os dias de vencimento que dá para escolher, com o fechamento de cada um
     */
    public record Status(UUID cardId, int dueDay, int closingDay, boolean canChange, String blockedReason,
                         LocalDate nextChangeAllowedOn, List<Option> options) {}

    /**
     * A fatura que absorve a troca.
     *
     * @param purchaseDays dias de compras que ela junta (um mês tem cerca de 30)
     */
    public record Transition(String referenceMonth, LocalDate periodStart, LocalDate closingDate, LocalDate dueDate,
                             long purchaseDays, String note) {}

    /** @param transition nulo quando o cartão não tem fatura aberta (a próxima já nasce nas datas novas) */
    public record Change(UUID cardId, int previousDueDay, int previousClosingDay, int dueDay, int closingDay,
                         Transition transition, LocalDate nextChangeAllowedOn) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final DueDayChangeRepository changes;
    private final CardEventPublisher eventPublisher;

    public DueDays(CardRepository cardRepository, InvoiceRepository invoiceRepository, DueDayChangeRepository changes,
                   CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.changes = changes;
        this.eventPublisher = eventPublisher;
    }

    public Status status(UUID cardId) {
        Card card = findCard(cardId);
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        LocalDate nextAllowed = nextChangeAllowedOn(cardId);
        String blocked = blockedReason(card, invoices, nextAllowed);
        List<Option> options = IntStream.rangeClosed(BillingDayPolicy.MIN_DAY, BillingDayPolicy.MAX_DAY)
                .filter(day -> day != card.getDueDay())
                .mapToObj(day -> new Option(day, BillingDayPolicy.closingDayFor(day)))
                .toList();
        return new Status(cardId, card.getDueDay(), card.getClosingDay(), blocked == null, blocked, nextAllowed, options);
    }

    @Transactional
    public Change change(UUID cardId, Integer dueDay) {
        if (dueDay == null) throw new ValidationException("Dia de vencimento é obrigatório");
        if (dueDay < BillingDayPolicy.MIN_DAY || dueDay > BillingDayPolicy.MAX_DAY) {
            throw new ValidationException("Dia de vencimento deve estar entre " + BillingDayPolicy.MIN_DAY + " e "
                    + BillingDayPolicy.MAX_DAY);
        }
        Card card = findCard(cardId);
        List<Invoice> invoices = invoiceRepository.findByCardId(cardId);
        String blocked = blockedReason(card, invoices, nextChangeAllowedOn(cardId));
        if (blocked != null) throw new BusinessRuleException(blocked);

        int previousClosing = card.getClosingDay();
        int previousDue = card.getDueDay();
        int closingDay = BillingDayPolicy.closingDayFor(dueDay);
        card.changeBillingDays(closingDay, dueDay);
        cardRepository.save(card);

        List<Invoice> open = invoices.stream().filter(i -> i.getStatus() == InvoiceStatus.OPEN)
                .sorted(Comparator.comparing(Invoice::getReferenceMonth)).toList();
        Invoice current = open.isEmpty() ? null : open.get(0);
        for (Invoice future : open.stream().skip(1).toList()) {
            future.reschedule(closingDay, dueDay);
            invoiceRepository.save(future);
        }

        LocalDateTime now = DomainTime.now();
        changes.insert(new DueDayChangeRepository.Change(cardId, previousClosing, previousDue, closingDay, dueDay, now));
        Transition transition = current == null ? null : transition(current, closingDay, dueDay);
        eventPublisher.publishDueDayChanged(cardId, dueDay, closingDay,
                transition == null ? null : transition.referenceMonth(), transition == null ? null : transition.dueDate(),
                transition == null ? null : transition.purchaseDays());
        return new Change(cardId, previousDue, previousClosing, dueDay, closingDay, transition,
                now.toLocalDate().plusDays(BillingDayPolicy.DAYS_BETWEEN_CHANGES));
    }

    /** A seguinte à atual: vai do fechamento (antigo) da atual até o fechamento novo. */
    private static Transition transition(Invoice current, int closingDay, int dueDay) {
        YearMonth month = current.getReferenceMonth().plusMonths(1);
        LocalDate closing = month.atDay(closingDay);
        LocalDate due = Invoice.dueDateOf(month, closingDay, dueDay);
        LocalDate start = current.getClosingDate().plusDays(1);
        long days = ChronoUnit.DAYS.between(current.getClosingDate(), closing);
        String length = days < 25 ? ", mais curta que o normal" : days > 35 ? ", mais longa que o normal" : "";
        String note = "A fatura de " + month.format(MONTH) + " é a de transição: junta as compras de "
                + start.format(DATE) + " a " + closing.format(DATE) + " (" + days + " dias" + length + ") e vence em "
                + due.format(DATE) + ". Depois, todas fecham dia " + closingDay + " e vencem dia " + dueDay + ".";
        return new Transition(month.toString(), start, closing, due, days, note);
    }

    private static String blockedReason(Card card, List<Invoice> invoices, LocalDate nextAllowed) {
        if (card.getStatus() == CardStatus.CANCELLED) return "Cartão cancelado";
        LocalDate today = DomainTime.today();
        if (invoices.stream().anyMatch(i -> i.isOverdue(today) && !i.remaining().isZero())) {
            return "Há fatura vencida em aberto: pague antes de trocar o vencimento";
        }
        if (nextAllowed != null) {
            return "O vencimento foi trocado há menos de " + BillingDayPolicy.DAYS_BETWEEN_CHANGES
                    + " dias: a próxima troca pode ser a partir de " + nextAllowed.format(FULL_DATE);
        }
        return null;
    }

    /** Nulo quando já pode trocar. */
    private LocalDate nextChangeAllowedOn(UUID cardId) {
        return changes.lastChangeAt(cardId)
                .map(last -> last.toLocalDate().plusDays(BillingDayPolicy.DAYS_BETWEEN_CHANGES))
                .filter(allowed -> allowed.isAfter(DomainTime.today()))
                .orElse(null);
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
