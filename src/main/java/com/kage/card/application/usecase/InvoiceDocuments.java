package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.CardPlastic;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.entity.VirtualCard;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.repository.CardPlasticRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.VirtualCardRepository;
import com.kage.card.domain.service.CardHolderDirectory;
import com.kage.card.domain.service.CetPolicy;
import com.kage.card.domain.service.InstallmentPolicy;
import com.kage.card.domain.service.RevolvingCreditPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.ReceiptCode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fatura fechada como documento (PDF): o que a Res. CMN 4.549 e o CDC pedem que o cliente veja —
 * total, mínimo, vencimento, lançamentos, limite e as taxas de juros (mensais e o equivalente anual),
 * para ele comparar o custo do rotativo com o do parcelamento antes de decidir. Só sai de fatura
 * fechada: a aberta ainda muda a cada compra.
 */
public class InvoiceDocuments {

    /** @param credit linha que abate o total (crédito de contestação, saldo credor, estorno) */
    public record Line(LocalDate date, String description, BigDecimal amount, boolean credit, String virtualCard) {}

    /** @param display texto pronto no lugar de "x% ao mês • y% ao ano" (ex.: a fórmula do IOF) */
    public record Rate(String name, BigDecimal monthly, BigDecimal yearly, String display) {
        public Rate(String name, BigDecimal monthly, BigDecimal yearly) {
            this(name, monthly, yearly, null);
        }
    }

    public record Statement(String holderName, String cardLastDigits, String referenceMonth, LocalDate closingDate,
                            LocalDate dueDate, String status, BigDecimal total, BigDecimal minimumPayment,
                            BigDecimal paidAmount, BigDecimal remaining, BigDecimal creditLimit, BigDecimal availableLimit,
                            List<Line> lines, List<Rate> rates, String authenticationCode, LocalDateTime generatedAt) {}

    /** Desenha o documento. A implementação (PDFBox) fica na infraestrutura. */
    public interface Renderer {
        byte[] render(Statement statement);
    }

    public record Document(String fileName, byte[] content) {}

    private static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_EVEN);

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final CardPlasticRepository plasticRepository;
    private final VirtualCardRepository virtualCardRepository;
    private final CardHolderDirectory holders;
    private final Renderer renderer;

    public InvoiceDocuments(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                            CardPlasticRepository plasticRepository, VirtualCardRepository virtualCardRepository,
                            CardHolderDirectory holders, Renderer renderer) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.plasticRepository = plasticRepository;
        this.virtualCardRepository = virtualCardRepository;
        this.holders = holders;
        this.renderer = renderer;
    }

    public Document pdf(UUID cardId, String referenceMonth) {
        Statement statement = statement(cardId, referenceMonth);
        return new Document("fatura-kage-" + statement.referenceMonth() + ".pdf", renderer.render(statement));
    }

    public Statement statement(UUID cardId, String referenceMonth) {
        YearMonth month = ReferenceMonths.parse(referenceMonth);
        Card card = cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
        Invoice invoice = invoiceRepository.findByCardIdAndReferenceMonth(cardId, month)
                .orElseThrow(() -> new NotFoundException("Fatura não encontrada para o mês " + month));
        if (invoice.getStatus() == InvoiceStatus.OPEN) {
            throw new BusinessRuleException("Fatura ainda aberta: o PDF sai no fechamento");
        }

        Map<UUID, String> nicknames = new HashMap<>();
        List<Line> lines = invoice.getItems().stream()
                .sorted(Comparator.comparing(item -> item.purchasedAt()))
                .map(item -> new Line(item.purchasedAt().toLocalDate(), item.description(), item.amount().amount(),
                        item.isCredit(), item.virtualCardId() == null ? null
                                : nicknames.computeIfAbsent(item.virtualCardId(), this::nickname)))
                .toList();

        String lastDigits = plasticRepository.findByCardId(cardId).stream()
                .filter(CardPlastic::isActive).findFirst()
                .or(() -> plasticRepository.findByCardId(cardId).stream().findFirst())
                .map(CardPlastic::lastDigits).orElse(null);

        List<Rate> rates = List.of(
                rate("Juros do rotativo", RevolvingCreditPolicy.REVOLVING_MONTHLY_RATE),
                rate("Juros do parcelamento da fatura", InstallmentPolicy.INVOICE_MONTHLY_RATE),
                rate("Juros de mora (atraso)", RevolvingCreditPolicy.LATE_INTEREST_MONTHLY_RATE),
                new Rate("Multa por atraso (uma vez)", RevolvingCreditPolicy.LATE_FINE_RATE, null),
                new Rate("IOF (rotativo e parcelamento)", null, null, "0,38% + 0,0082% ao dia"),
                cet("CET do rotativo (30 dias)", CetPolicy.revolvingReference(invoice.getDueDate())),
                cet("CET do parcelamento em 12x", CetPolicy.installmentReference(invoice.getDueDate(), 12)));

        return new Statement(holders.nameOf(card.getCustomerId()).orElse("Titular"), lastDigits, month.toString(),
                invoice.getClosingDate(), invoice.getDueDate(), invoice.getStatus().name(), invoice.total().amount(),
                invoice.minimumPayment().amount(), invoice.getPaidAmount().amount(), invoice.remaining().amount(),
                card.getCreditLimit().amount(), card.availableLimit().amount(), lines, rates,
                ReceiptCode.of("FATURA", invoice.getId(), invoice.total().amount(), invoice.getClosingDate(), cardId),
                DomainTime.now());
    }

    private String nickname(UUID virtualCardId) {
        return virtualCardRepository.findById(virtualCardId).map(VirtualCard::nickname).orElse("virtual");
    }

    private static Rate cet(String name, CetPolicy.Cet cet) {
        return new Rate(name, cet.monthly(), cet.yearly());
    }

    /** Taxa mensal e o equivalente anual com capitalização: (1 + i)^12 - 1. */
    static Rate rate(String name, BigDecimal monthly) {
        BigDecimal yearly = BigDecimal.ONE.add(monthly).pow(12, PRECISION).subtract(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP);
        return new Rate(name, monthly, yearly);
    }
}
