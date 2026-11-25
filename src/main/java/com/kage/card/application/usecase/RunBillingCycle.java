package com.kage.card.application.usecase;

import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.InvoiceRepository.InvoiceRef;
import com.kage.shared.domain.exception.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ciclo de cobrança do dia, na ordem em que um banco faz:
 * 1. fecha as faturas abertas cujo dia de corte já passou;
 * 2. no vencimento, paga com o saldo da conta as faturas fechadas dos cartões com débito automático.
 *
 * Cada fatura roda na própria transação (os use cases chamados são @Transactional): uma falha não
 * desfaz as outras nem para o lote. Sem saldo no vencimento, a fatura fica CLOSED (em atraso) e é
 * tentada de novo no dia seguinte — enquanto o cliente pode pagar manualmente quando quiser.
 *
 * Idempotente: rodar duas vezes no dia (ou em duas instâncias) não fecha nem cobra duas vezes —
 * Invoice.close()/pay() recusam o segundo, e o @Version derruba a corrida.
 *
 * Deliberadamente sem @Transactional: uma transação única deixaria uma fatura com problema
 * desfazer o ciclo inteiro.
 */
public class RunBillingCycle {

    private static final Logger logger = LoggerFactory.getLogger(RunBillingCycle.class);

    public record Failure(UUID cardId, String referenceMonth, String step, String reason) {}
    public record Output(LocalDate date, int invoicesClosed, int invoicesPaid, List<Failure> failures) {}

    private final InvoiceRepository invoiceRepository;
    private final CloseInvoice closeInvoice;
    private final PayInvoice payInvoice;
    private final CardEventPublisher eventPublisher;

    public RunBillingCycle(InvoiceRepository invoiceRepository, CloseInvoice closeInvoice, PayInvoice payInvoice,
                           CardEventPublisher eventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.closeInvoice = closeInvoice;
        this.payInvoice = payInvoice;
        this.eventPublisher = eventPublisher;
    }

    public Output execute(LocalDate today) {
        List<Failure> failures = new ArrayList<>();

        int closed = 0;
        for (InvoiceRef ref : invoiceRepository.findOpenWithClosingDateBefore(today)) {
            if (attempt(ref, "CLOSE", failures,
                    () -> closeInvoice.execute(new CloseInvoice.Input(ref.cardId(), ref.referenceMonth().toString())))) {
                closed++;
            }
        }

        int paid = 0;
        for (InvoiceRef ref : invoiceRepository.findClosedDueUntilWithAutoDebit(today)) {
            if (attempt(ref, "AUTO_DEBIT", failures,
                    () -> payInvoice.execute(new PayInvoice.Input(ref.cardId(), ref.referenceMonth().toString())))) {
                paid++;
            } else {
                // a transação do pagamento já sofreu rollback: o aviso sai numa transação própria
                Failure failure = failures.get(failures.size() - 1);
                eventPublisher.publishAutoDebitFailed(ref.cardId(), failure.referenceMonth(), failure.reason());
            }
        }

        logger.info("Ciclo de cobrança de {}: {} fatura(s) fechada(s), {} paga(s) por débito automático, {} falha(s)",
                today, closed, paid, failures.size());
        return new Output(today, closed, paid, failures);
    }

    private boolean attempt(InvoiceRef ref, String step, List<Failure> failures, Runnable action) {
        try {
            action.run();
            return true;
        } catch (DomainException | OptimisticLockingFailureException e) {
            // regra de negócio (sem saldo, conta bloqueada, já processada por outra instância): segue o lote
            logger.warn("Ciclo de cobrança: {} falhou para o cartão {} ({}): {}", step, ref.cardId(), ref.referenceMonth(), e.getMessage());
            failures.add(new Failure(ref.cardId(), ref.referenceMonth().toString(), step, e.getMessage()));
            return false;
        }
    }
}
