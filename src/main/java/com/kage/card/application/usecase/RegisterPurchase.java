package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.entity.VirtualCard;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.PurchaseChannel;
import com.kage.card.domain.repository.InternationalPurchaseRepository;
import com.kage.card.domain.service.ExchangeRateProvider;
import com.kage.card.domain.service.InternationalPurchasePolicy;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import com.kage.card.domain.service.InstallmentPolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RegisterPurchase {

    /**
     * @param amount            na moeda da compra ({@code currency})
     * @param installments      parcelas sem juros (1 = à vista; nulo também)
     * @param virtualCardNumber compra feita com o número de um cartão virtual deste cartão; nulo = o físico
     * @param channel           CHIP, CONTACTLESS ou ONLINE; nulo = CHIP no físico, ONLINE no virtual
     * @param country           país do estabelecimento (ISO, 2 letras); nulo = BR
     * @param currency          BRL, USD, EUR ou GBP; nulo = BRL. Moeda estrangeira: conversão e IOF
     *                          ({@link InternationalPurchasePolicy})
     */
    public record Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                        Integer installments, String virtualCardNumber, String channel, String country,
                        String currency) {
        public Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                     Integer installments, String virtualCardNumber, String channel, String country) {
            this(cardId, purchaseId, description, amount, purchasedAt, installments, virtualCardNumber, channel, country,
                    null);
        }

        public Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                     Integer installments, String virtualCardNumber) {
            this(cardId, purchaseId, description, amount, purchasedAt, installments, virtualCardNumber, null, null);
        }

        public Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt,
                     Integer installments) {
            this(cardId, purchaseId, description, amount, purchasedAt, installments, null);
        }

        public Input(UUID cardId, UUID purchaseId, String description, BigDecimal amount, LocalDateTime purchasedAt) {
            this(cardId, purchaseId, description, amount, purchasedAt, 1);
        }
    }

    /**
     * @param invoiceId     fatura da 1ª parcela; {@code installmentAmount} é o valor dela (em reais)
     * @param international compra em moeda estrangeira: a conversão e o IOF; nulo em reais
     */
    public record Output(UUID cardId, BigDecimal availableLimit, UUID invoiceId, String referenceMonth,
                         BigDecimal invoiceTotal, int installments, BigDecimal installmentAmount, boolean duplicate,
                         UUID virtualCardId, InternationalPurchasePolicy.Quote international) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final VirtualCards virtualCards;
    private final CardPlastics plastics;
    private final CardEventPublisher eventPublisher;
    private final LimitUsageAlerts limitUsageAlerts;
    private final CardPurchaseControls purchaseControls;
    private final ExchangeRateProvider exchangeRates;
    private final InternationalPurchaseRepository internationalPurchases;

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository) {
        this(cardRepository, invoiceRepository, null, null, null, null);
    }

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository, VirtualCards virtualCards,
                            CardPlastics plastics, CardEventPublisher eventPublisher, LimitUsageAlerts limitUsageAlerts) {
        this(cardRepository, invoiceRepository, virtualCards, plastics, eventPublisher, limitUsageAlerts, null);
    }

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository, VirtualCards virtualCards,
                            CardPlastics plastics, CardEventPublisher eventPublisher) {
        this(cardRepository, invoiceRepository, virtualCards, plastics, eventPublisher, null);
    }

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository, VirtualCards virtualCards,
                            CardPlastics plastics, CardEventPublisher eventPublisher, LimitUsageAlerts limitUsageAlerts,
                            CardPurchaseControls purchaseControls) {
        this(cardRepository, invoiceRepository, virtualCards, plastics, eventPublisher, limitUsageAlerts, purchaseControls,
                null, null);
    }

    public RegisterPurchase(CardRepository cardRepository, InvoiceRepository invoiceRepository, VirtualCards virtualCards,
                            CardPlastics plastics, CardEventPublisher eventPublisher, LimitUsageAlerts limitUsageAlerts,
                            CardPurchaseControls purchaseControls, ExchangeRateProvider exchangeRates,
                            InternationalPurchaseRepository internationalPurchases) {
        this.exchangeRates = exchangeRates;
        this.internationalPurchases = internationalPurchases;
        this.purchaseControls = purchaseControls;
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.virtualCards = virtualCards;
        this.plastics = plastics;
        this.eventPublisher = eventPublisher;
        this.limitUsageAlerts = limitUsageAlerts;
    }

    @Transactional
    public Output execute(Input input) {
        if (input.amount() == null || input.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Valor da compra deve ser maior que zero");
        }

        Card card = cardRepository.findById(input.cardId())
                .orElseThrow(() -> new NotFoundException("Cartão não encontrado"));

        Optional<Invoice> alreadyRegistered = invoiceRepository.findByCardIdAndPurchaseId(card.getId(), input.purchaseId());
        if (alreadyRegistered.isPresent()) {
            return toOutput(card, alreadyRegistered.get(), input.purchaseId(), true,
                    internationalPurchases == null ? null : internationalPurchases.find(input.purchaseId()).orElse(null));
        }

        // o virtual precisa ser deste cartão e estar valendo; o físico (status, limite) é checado abaixo
        UUID virtualCardId = null;
        String virtualNickname = null;
        if (input.virtualCardNumber() != null) {
            if (virtualCards == null) throw new IllegalStateException("Cartões virtuais não configurados");
            VirtualCard virtual = virtualCards.authorize(card.getId(), input.virtualCardNumber());
            virtualCardId = virtual.id();
            virtualNickname = virtual.nickname();
        } else if (plastics != null && card.getStatus() == CardStatus.ACTIVE) {
            // cartão bloqueado/cancelado: a recusa é a do cartão (authorizePurchase); ativo, falta checar o plástico
            plastics.requirePhysicalUsable(card.getId()); // 2ª via a caminho ou plástico cancelado
        }

        // o que o cliente desligou (online no físico, internacional, aproximação); cartão bloqueado ou
        // cancelado recusa pelo próprio status logo abaixo
        PurchaseChannel channel = channelOf(input.channel(), virtualCardId != null);
        String currency = InternationalPurchasePolicy.currencyOf(input.currency());
        int installments = input.installments() == null ? 1 : input.installments();
        // moeda estrangeira é compra internacional, mesmo sem o país informado
        boolean abroad = currency != null || !"BR".equals(countryOf(input.country()));
        if (abroad && installments != 1) throw new ValidationException("Compra internacional é à vista");
        if (purchaseControls != null && card.getStatus() == CardStatus.ACTIVE) {
            purchaseControls.authorize(card.getId(), channel, abroad, virtualCardId != null);
        }

        // em moeda estrangeira: convertida pela cotação do dia da compra, com IOF à parte
        InternationalPurchasePolicy.Quote fx = null;
        if (currency != null) {
            if (exchangeRates == null) throw new IllegalStateException("Câmbio não configurado");
            fx = InternationalPurchasePolicy.quote(currency, input.amount(),
                    exchangeRates.sellingRate(currency, input.purchasedAt().toLocalDate()));
        } else if (abroad) {
            // em reais num estabelecimento de fora: sem conversão, mas com o IOF da operação internacional
            fx = InternationalPurchasePolicy.inReais(input.amount(), input.purchasedAt().toLocalDate());
        }
        Money total = fx == null ? new Money(input.amount()) : Money.of(fx.brl());
        Money iof = fx == null ? Money.ZERO : Money.of(fx.iof());
        List<Money> parts = InstallmentPolicy.splitWithoutInterest(total, installments);

        // o limite é consumido pelo valor TOTAL na hora (como nos cartões de verdade), IOF incluso, e
        // volta conforme cada fatura é paga
        card.authorizePurchase(total.add(iof));

        Invoice first = null;
        YearMonth month = Invoice.referenceMonthFor(input.purchasedAt().toLocalDate(), card.getClosingDay());
        for (int number = 1; number <= installments; number++) {
            // só a 1ª parcela segue a data da compra; as seguintes vão para os meses depois dela
            Invoice invoice = findOpenInvoiceFrom(card, month, number == 1 ? input.purchasedAt().toLocalDate() : null);
            String description = installments == 1
                    ? input.description()
                    : input.description() + " (" + number + "/" + installments + ")";
            if (currency != null) description = input.description() + " (" + foreign(fx) + ")";
            invoice.addInstallment(input.purchaseId(), description, parts.get(number - 1), input.purchasedAt(),
                    number, installments, virtualCardId);
            if (fx != null) {
                invoice.addCharge(InvoiceItemKind.IOF, input.purchaseId(), "IOF da compra internacional ("
                        + InternationalPurchasePolicy.IOF_PERCENT.replace('.', ',') + "%): " + input.description(), iof,
                        input.purchasedAt());
            }
            Invoice saved = invoiceRepository.save(invoice);
            if (first == null) first = saved;
            month = saved.getReferenceMonth().plusMonths(1); // a próxima parcela vai para o mês seguinte
        }

        Card savedCard = cardRepository.save(card);
        if (fx != null && internationalPurchases != null) {
            internationalPurchases.insert(savedCard.getId(), input.purchaseId(), fx, input.purchasedAt());
        }
        if (eventPublisher != null) {
            eventPublisher.publishPurchaseApproved(savedCard.getId(), input.purchaseId(), input.description(), total,
                    installments, parts.get(0), savedCard.availableLimit(), virtualNickname);
        }
        if (limitUsageAlerts != null) {
            // "uma vez por fatura": a fatura em que a compra (ou a 1ª parcela) caiu
            limitUsageAlerts.afterPurchase(savedCard, first.getReferenceMonth());
        }
        return toOutput(savedCard, first, input.purchaseId(), false, fx);
    }

    /** "USD 100,00 a R$ 5,7200" */
    private static String foreign(InternationalPurchasePolicy.Quote fx) {
        java.text.DecimalFormat money = new java.text.DecimalFormat("#,##0.00",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.forLanguageTag("pt-BR")));
        java.text.DecimalFormat rate = new java.text.DecimalFormat("#,##0.0000",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.forLanguageTag("pt-BR")));
        return fx.currency() + " " + money.format(fx.foreignAmount()) + " a R$ " + rate.format(fx.rate());
    }

    /**
     * Fatura que recebe a parcela: a do mês informado e, se ela já foi fechada (ou paga), a primeira
     * fatura seguinte que ainda esteja aberta, criando-a se não existir. É o que um banco faz com a
     * compra que chega depois do fechamento — e é assim que as faturas futuras de uma compra
     * parcelada passam a existir.
     *
     * Com {@code purchaseDate}, vale o fechamento gravado em cada fatura, não o dia do cartão: depois de
     * uma troca de vencimento, a fatura atual fecha no dia antigo. Se ele é mais tarde que o novo, a
     * compra ainda cabe na fatura do mês anterior; se é mais cedo, a fatura do mês já fechou para ela.
     */
    private Invoice findOpenInvoiceFrom(Card card, YearMonth from, LocalDate purchaseDate) {
        if (purchaseDate != null) {
            Optional<Invoice> previous = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), from.minusMonths(1));
            if (previous.isPresent() && previous.get().acceptsPurchaseOn(purchaseDate)) return previous.get();
        }
        YearMonth month = from;
        while (true) {
            Optional<Invoice> existing = invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), month);
            if (existing.isEmpty()) {
                return Invoice.open(card.getId(), month, card.getClosingDay(), card.getDueDay());
            }
            Invoice invoice = existing.get();
            if (invoice.getStatus() == InvoiceStatus.OPEN
                    && (purchaseDate == null || invoice.acceptsPurchaseOn(purchaseDate))) {
                return invoice;
            }
            month = month.plusMonths(1);
        }
    }

    private static PurchaseChannel channelOf(String raw, boolean virtualCard) {
        if (raw == null) return virtualCard ? PurchaseChannel.ONLINE : PurchaseChannel.CHIP;
        PurchaseChannel channel;
        try {
            channel = PurchaseChannel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Canal da compra inválido: use CHIP, CONTACTLESS ou ONLINE");
        }
        // o virtual não existe em plástico: não vai à maquininha
        if (virtualCard && channel != PurchaseChannel.ONLINE) {
            throw new ValidationException("Cartão virtual só compra online");
        }
        return channel;
    }

    private static String countryOf(String raw) {
        if (raw == null) return "BR";
        String country = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!country.matches("[A-Z]{2}")) throw new ValidationException("País da compra com 2 letras (ISO), ex.: BR, US");
        return country;
    }

    private Output toOutput(Card card, Invoice invoice, UUID purchaseId, boolean duplicate,
                            InternationalPurchasePolicy.Quote fx) {
        var item = invoice.getItems().stream().filter(i -> i.purchaseId().equals(purchaseId)).findFirst().orElseThrow();
        return new Output(card.getId(), card.availableLimit().amount(), invoice.getId(),
                invoice.getReferenceMonth().toString(), invoice.total().amount(), item.installmentCount(),
                item.amount().amount(), duplicate, item.virtualCardId(), fx);
    }
}
