package com.kage.card.application.usecase;

import com.kage.shared.domain.DomainTime;
import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.service.CardBillingAccountService;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Paga a fatura fechada com o saldo da conta vinculada ao cartão, tudo numa transação: débito na
 * conta (com lançamento no extrato), limite liberado no valor pago e a fatura atualizada.
 *
 * Sem valor, paga o saldo devedor inteiro (é o que o débito automático usa). Com valor, é pagamento
 * parcial: a fatura continua CLOSED até zerar, e o que sobrar no próximo fechamento vai para o
 * rotativo (com encargos de atraso se não chegou ao mínimo). Sem saldo na conta, nada muda.
 *
 * Cartão bloqueado ou cancelado continua podendo pagar: a dívida não some com o bloqueio.
 */
public class PayInvoice {

    /** @param amount nulo = saldo devedor inteiro */
    public record Input(UUID cardId, String referenceMonth, BigDecimal amount) {
        public Input(UUID cardId, String referenceMonth) {
            this(cardId, referenceMonth, null);
        }
    }

    public record Output(UUID invoiceId, UUID cardId, String referenceMonth, String status, BigDecimal amountPaid,
                         BigDecimal remaining, LocalDateTime paidAt, BigDecimal availableLimit) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CardBillingAccountService billingAccount;
    private final CardEventPublisher eventPublisher;
    private final Cashbacks cashbacks;

    public PayInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                      CardBillingAccountService billingAccount, CardEventPublisher eventPublisher) {
        this(cardRepository, invoiceRepository, billingAccount, eventPublisher, null);
    }

    public PayInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                      CardBillingAccountService billingAccount, CardEventPublisher eventPublisher, Cashbacks cashbacks) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.billingAccount = billingAccount;
        this.eventPublisher = eventPublisher;
        this.cashbacks = cashbacks;
    }

    @Transactional
    public Output execute(Input input) {
        YearMonth referenceMonth = ReferenceMonths.parse(input.referenceMonth());

        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), referenceMonth)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + referenceMonth));

        // primeiro a regra da fatura (só CLOSED, até o saldo devedor): nada é debitado se ela recusar
        Money amount;
        if (input.amount() == null) {
            amount = invoice.remaining();
            invoice.pay();
        } else {
            amount = Money.of(input.amount());
            invoice.registerPayment(amount, DomainTime.now());
        }

        if (!amount.isZero()) {
            billingAccount.debitInvoicePayment(card.getAccountId(), amount, invoice.getId());
            card.releaseLimit(amount);
        }
        // quitou a fatura que carregava o rotativo: a dívida acabou, o teto recomeça do zero
        if (invoice.getStatus() == InvoiceStatus.PAID && invoice.hasPreviousBalance()) {
            card.settleRevolving();
        }

        Card savedCard = cardRepository.save(card);
        Invoice savedInvoice = invoiceRepository.save(invoice);
        if (savedInvoice.getStatus() == InvoiceStatus.PAID) {
            eventPublisher.publishInvoicePaid(savedInvoice);
            if (cashbacks != null) cashbacks.creditFor(savedCard, invoice); // paga em dia: cashback na seguinte
        } else {
            eventPublisher.publishInvoicePartiallyPaid(savedInvoice, amount);
        }

        return new Output(savedInvoice.getId(), savedCard.getId(), savedInvoice.getReferenceMonth().toString(),
                savedInvoice.getStatus().name(), amount.amount(), savedInvoice.remaining().amount(),
                savedInvoice.getPaidAt(), savedCard.availableLimit().amount());
    }
}
