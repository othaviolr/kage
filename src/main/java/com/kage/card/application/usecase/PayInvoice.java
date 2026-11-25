package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
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
 * Paga a fatura fechada com o saldo da conta vinculada ao cartão, tudo numa transação:
 * fatura CLOSED -> PAID, débito na conta (com lançamento no extrato) e o limite do cartão liberado.
 * Sem saldo, nada muda. Pagamento é sempre do total (sem mínimo/rotativo por enquanto).
 *
 * Cartão bloqueado ou cancelado continua podendo pagar: a dívida não some com o bloqueio.
 */
public class PayInvoice {

    public record Input(UUID cardId, String referenceMonth) {}
    public record Output(UUID invoiceId, UUID cardId, String referenceMonth, String status, BigDecimal amountPaid,
                         LocalDateTime paidAt, BigDecimal availableLimit) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CardBillingAccountService billingAccount;

    public PayInvoice(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                      CardBillingAccountService billingAccount) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.billingAccount = billingAccount;
    }

    @Transactional
    public Output execute(Input input) {
        YearMonth referenceMonth = ReferenceMonths.parse(input.referenceMonth());

        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), referenceMonth)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + referenceMonth));

        // primeiro a regra da fatura (só CLOSED paga): pagar duas vezes falha antes de debitar de novo
        invoice.pay();
        Money total = invoice.total();

        if (!total.isZero()) {
            billingAccount.debitInvoicePayment(card.getAccountId(), total, invoice.getId());
            card.releaseLimit(total);
        }

        Card savedCard = cardRepository.save(card);
        Invoice savedInvoice = invoiceRepository.save(invoice);

        return new Output(savedInvoice.getId(), savedCard.getId(), savedInvoice.getReferenceMonth().toString(),
                savedInvoice.getStatus().name(), total.amount(), savedInvoice.getPaidAt(),
                savedCard.availableLimit().amount());
    }
}
