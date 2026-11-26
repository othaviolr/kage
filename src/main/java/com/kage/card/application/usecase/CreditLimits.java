package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.LimitOffer;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.LimitOfferRepository;
import com.kage.card.domain.service.CreditLimitReviewPolicy;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Limite do cartão: o cliente ajusta o limite em uso (para baixo ou para cima, até o aprovado, na
 * hora); o banco analisa o comportamento (todo mês, ou quando o cliente pede) e oferece um limite
 * aprovado maior, que o cliente aceita ou recusa.
 */
public class CreditLimits {

    private static final Logger logger = LoggerFactory.getLogger(CreditLimits.class);

    public record Limits(UUID cardId, BigDecimal creditLimit, BigDecimal approvedLimit, BigDecimal usedLimit,
                         BigDecimal availableLimit) {}

    public record OfferOutput(UUID offerId, UUID cardId, BigDecimal previousLimit, BigDecimal offeredLimit, String status,
                              LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime respondedAt) {}

    /** @param offer a oferta (nova ou a que já estava aberta), quando aprovado; reasons, quando não */
    public record ReviewOutput(boolean approved, OfferOutput offer, List<String> reasons) {}

    public record RunOutput(LocalDate date, int cardsReviewed, int offersCreated) {}

    private final CardRepository cardRepository;
    private final InvoiceRepository invoiceRepository;
    private final LimitOfferRepository offerRepository;
    private final CardEventPublisher eventPublisher;
    private final TransactionTemplate transactions;

    public CreditLimits(CardRepository cardRepository, InvoiceRepository invoiceRepository,
                        LimitOfferRepository offerRepository, CardEventPublisher eventPublisher,
                        TransactionTemplate transactions) {
        this.cardRepository = cardRepository;
        this.invoiceRepository = invoiceRepository;
        this.offerRepository = offerRepository;
        this.eventPublisher = eventPublisher;
        this.transactions = transactions;
    }

    /** Ajusta o limite em uso: para baixo na hora; para cima, até o limite aprovado. */
    @Transactional
    public Limits adjust(UUID cardId, BigDecimal amount) {
        if (amount == null) throw new ValidationException("Informe o novo limite (amount)");
        Card card = findCard(cardId);
        card.adjustLimit(Money.of(amount));
        return toLimits(cardRepository.save(card));
    }

    public Limits limits(UUID cardId) {
        return toLimits(findCard(cardId));
    }

    /**
     * O cliente pede uma análise agora. Aprovado, nasce a oferta (e o aviso); se já há uma aberta, é
     * ela que volta. Recusado, vêm os motivos.
     */
    @Transactional
    public ReviewOutput requestReview(UUID cardId) {
        Card card = findCard(cardId);
        LocalDateTime now = DomainTime.now();
        Optional<LimitOffer> open = offerRepository.findByCardId(cardId).stream().filter(o -> o.isOpen(now)).findFirst();
        if (open.isPresent()) return new ReviewOutput(true, toOutput(open.get()), List.of());
        return review(card, DomainTime.today());
    }

    public List<OfferOutput> offers(UUID cardId) {
        findCard(cardId);
        return offerRepository.findByCardId(cardId).stream().map(this::toOutput).toList();
    }

    /** Aceita: o limite aprovado e o limite em uso sobem para o oferecido. */
    @Transactional
    public Limits accept(UUID cardId, UUID offerId) {
        LimitOffer offer = findOffer(cardId, offerId);
        LimitOffer accepted = offer.accept();
        if (!offerRepository.respond(accepted)) throw new ConflictException("A oferta de limite já foi respondida");
        Card card = findCard(cardId);
        card.raiseApprovedLimit(offer.offeredLimit());
        return toLimits(cardRepository.save(card));
    }

    @Transactional
    public OfferOutput decline(UUID cardId, UUID offerId) {
        LimitOffer declined = findOffer(cardId, offerId).decline();
        if (!offerRepository.respond(declined)) throw new ConflictException("A oferta de limite já foi respondida");
        return toOutput(declined);
    }

    /**
     * Rodada do banco (job mensal ou backoffice): analisa os cartões ativos e oferece a quem passar.
     * Cada cartão na própria transação — a oferta e o evento do aviso (outbox) entram juntos, e um
     * cartão com problema não desfaz os outros.
     */
    public RunOutput runReviews(LocalDate today) {
        List<UUID> cards = cardRepository.findActiveIds();
        int created = 0;
        for (UUID cardId : cards) {
            try {
                Boolean offered = transactions.execute(tx -> {
                    Card card = findCard(cardId);
                    LocalDateTime now = DomainTime.now();
                    if (offerRepository.findByCardId(cardId).stream().anyMatch(o -> o.isOpen(now))) return false;
                    return review(card, today).approved();
                });
                if (Boolean.TRUE.equals(offered)) created++;
            } catch (RuntimeException e) {
                logger.warn("Análise de limite do cartão {} fica para a próxima rodada: {}", cardId, e.getMessage());
            }
        }
        if (created > 0) logger.info("Análise de limite de {}: {} oferta(s) de aumento", today, created);
        return new RunOutput(today, cards.size(), created);
    }

    private ReviewOutput review(Card card, LocalDate today) {
        List<LimitOffer> history = offerRepository.findByCardId(card.getId());
        LocalDateTime lastOfferAt = history.isEmpty() ? null : history.get(0).createdAt();
        CreditLimitReviewPolicy.Review review = CreditLimitReviewPolicy.review(card,
                invoiceRepository.findByCardId(card.getId()), today, lastOfferAt);
        if (!review.approved()) return new ReviewOutput(false, null, review.reasons());

        LimitOffer offer = LimitOffer.create(card.getId(), card.getApprovedLimit(), review.offeredLimit());
        offerRepository.insert(offer);
        eventPublisher.publishLimitOffered(card.getId(), offer.id(), offer.offeredLimit(), offer.expiresAt());
        return new ReviewOutput(true, toOutput(offer), List.of());
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }

    private LimitOffer findOffer(UUID cardId, UUID offerId) {
        return offerRepository.findById(offerId).filter(o -> o.cardId().equals(cardId))
                .orElseThrow(() -> new NotFoundException("Oferta de limite não encontrada"));
    }

    private OfferOutput toOutput(LimitOffer o) {
        return new OfferOutput(o.id(), o.cardId(), o.previousLimit().amount(), o.offeredLimit().amount(),
                o.displayStatus(DomainTime.now()), o.createdAt(), o.expiresAt(), o.respondedAt());
    }

    private static Limits toLimits(Card card) {
        return new Limits(card.getId(), card.getCreditLimit().amount(), card.getApprovedLimit().amount(),
                card.getUsedLimit().amount(), card.availableLimit().amount());
    }
}
