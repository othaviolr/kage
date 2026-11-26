package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.CardPlastic;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.PlasticReason;
import com.kage.card.domain.enums.PlasticStatus;
import com.kage.card.domain.repository.CardPlasticRepository;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.service.CardNumberService;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Plásticos do cartão físico e a 2ª via.
 *
 * Pedir a 2ª via (perda, roubo, fraude, defeito) manda um plástico novo, com outro número, que só vale
 * depois que o cliente o desbloqueia com os 4 últimos dígitos. Na perda, no roubo e na fraude o
 * plástico antigo morre na hora — e, se o cartão estava bloqueado por segurança, ele volta a funcionar
 * (os virtuais voltam na hora; o físico, quando o novo for desbloqueado). No defeito, o antigo vale até
 * o novo ser desbloqueado. Limite, faturas e rotativo não mudam: é o mesmo cartão, plástico novo.
 * Sem tarifa de 2ª via (Res. CMN 3.919 permite cobrar, o Kage não cobra).
 */
public class CardPlastics {

    private static final int NUMBER_ATTEMPTS = 3;
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");

    public record Output(UUID plasticId, UUID cardId, int sequence, String maskedNumber, String expiry, String status,
                         String issueReason, LocalDateTime issuedAt, LocalDateTime activatedAt,
                         LocalDateTime cancelledAt, String cancelReason) {

        static Output of(CardPlastic p) {
            return new Output(p.id(), p.cardId(), p.sequence(), "•••• " + p.lastDigits(), p.expiry().format(EXPIRY),
                    p.status().name(), p.issueReason().name(), p.issuedAt(), p.activatedAt(), p.cancelledAt(),
                    p.cancelReason() == null ? null : p.cancelReason().name());
        }
    }

    /**
     * @param cardUnblocked o cartão estava bloqueado por segurança e voltou a funcionar (o número antigo morreu)
     * @param previous      o plástico que estava valendo; no defeito, segue ACTIVE até o novo ser desbloqueado
     */
    public record ReissueOutput(Output plastic, Output previous, boolean cardUnblocked) {}

    private final CardRepository cardRepository;
    private final CardPlasticRepository plasticRepository;
    private final CardNumberService numbers;
    private final CardEventPublisher eventPublisher;

    public CardPlastics(CardRepository cardRepository, CardPlasticRepository plasticRepository, CardNumberService numbers,
                        CardEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.plasticRepository = plasticRepository;
        this.numbers = numbers;
        this.eventPublisher = eventPublisher;
    }

    /** Plástico da emissão. Chamar na transação da emissão do cartão. */
    public CardPlastic issueFirst(UUID cardId) {
        return insertWithFreshNumber((id, number) -> CardPlastic.first(id, cardId, last4(number), numbers.hash(number)));
    }

    @Transactional
    public ReissueOutput reissue(UUID cardId, String reason) {
        PlasticReason parsed = parseReason(reason);
        Card card = findCard(cardId);
        if (card.getStatus() == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão cancelado: não há 2ª via");

        List<CardPlastic> plastics = plasticRepository.findByCardId(cardId);
        Optional<CardPlastic> active = plastics.stream().filter(CardPlastic::isActive).findFirst();
        Optional<CardPlastic> pending = plastics.stream().filter(CardPlastic::isPending).findFirst();

        if (pending.isPresent() && !parsed.compromisesNumber()) {
            throw new BusinessRuleException("Já existe uma 2ª via a caminho: desbloqueie-a quando chegar");
        }
        // 2ª via que se perdeu no caminho também morre: o número dela pode estar com outra pessoa
        pending.ifPresent(p -> plasticRepository.update(p.cancel(parsed)));

        CardPlastic previous = active.orElse(null);
        if (previous != null && parsed.compromisesNumber()) {
            previous = previous.cancel(parsed);
            plasticRepository.update(previous);
        }

        int sequence = plastics.stream().mapToInt(CardPlastic::sequence).max().orElse(0) + 1;
        CardPlastic created = insertWithFreshNumber((id, number) ->
                CardPlastic.replacement(id, cardId, sequence, last4(number), numbers.hash(number), parsed));

        boolean unblocked = false;
        if (parsed.compromisesNumber() && card.getStatus() == CardStatus.BLOCKED) {
            card.unblock(); // o bloqueio protegia o número antigo, que acabou de morrer
            cardRepository.save(card);
            unblocked = true;
        }
        eventPublisher.publishCardReissued(created, parsed.compromisesNumber());
        return new ReissueOutput(Output.of(created), previous == null ? null : Output.of(previous), unblocked);
    }

    /** Desbloqueio do cartão novo com os 4 últimos dígitos impressos nele. */
    @Transactional
    public Output activate(UUID cardId, String lastDigits) {
        Card card = findCard(cardId);
        if (card.getStatus() == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão cancelado");
        List<CardPlastic> plastics = plasticRepository.findByCardId(cardId);
        CardPlastic pending = plastics.stream().filter(CardPlastic::isPending).findFirst()
                .orElseThrow(() -> new BusinessRuleException("Não há cartão novo aguardando desbloqueio"));

        CardPlastic activated = pending.activate(lastDigits);
        // defeito: o antigo valia até aqui (primeiro cancela, depois ativa — um ACTIVE por cartão)
        plastics.stream().filter(CardPlastic::isActive)
                .forEach(old -> plasticRepository.update(old.cancel(PlasticReason.REPLACED)));
        plasticRepository.update(activated);
        return Output.of(activated);
    }

    public List<Output> list(UUID cardId) {
        findCard(cardId);
        return plasticRepository.findByCardId(cardId).stream().map(Output::of).toList();
    }

    /**
     * Compra com o cartão físico: precisa haver um plástico valendo. Cartão sem plástico registrado
     * (emitido antes da 2ª via existir) segue comprando.
     */
    public void requirePhysicalUsable(UUID cardId) {
        List<CardPlastic> plastics = plasticRepository.findByCardId(cardId);
        if (plastics.isEmpty() || plastics.stream().anyMatch(CardPlastic::isActive)) return;
        if (plastics.stream().anyMatch(CardPlastic::isPending)) {
            throw new BusinessRuleException("Cartão físico novo ainda não foi desbloqueado");
        }
        throw new BusinessRuleException("Cartão físico cancelado: peça a 2ª via");
    }

    /** Cartão cancelado: os plásticos morrem com ele. Chamar na transação do cancelamento. */
    public void cancelAllOf(UUID cardId) {
        plasticRepository.findByCardId(cardId).stream()
                .filter(p -> p.status() != PlasticStatus.CANCELLED)
                .forEach(p -> plasticRepository.update(p.cancel(PlasticReason.CARD_CANCELLED)));
    }

    private CardPlastic insertWithFreshNumber(BiFunction<UUID, String, CardPlastic> factory) {
        for (int attempt = 1; attempt <= NUMBER_ATTEMPTS; attempt++) {
            UUID id = UUID.randomUUID();
            CardPlastic plastic = factory.apply(id, numbers.numberFor(id));
            if (plasticRepository.insert(plastic)) return plastic;
        }
        throw new IllegalStateException("Não foi possível gerar um número livre para o cartão");
    }

    private static String last4(String number) {
        return number.substring(number.length() - 4);
    }

    private static PlasticReason parseReason(String reason) {
        if (reason == null || reason.isBlank()) throw new ValidationException("Informe o motivo da 2ª via (reason)");
        try {
            PlasticReason parsed = PlasticReason.valueOf(reason.strip().toUpperCase());
            if (parsed.isReissueReason()) return parsed;
        } catch (IllegalArgumentException ignored) {
            // cai na mensagem abaixo
        }
        throw new ValidationException("Motivo inválido: use LOST, STOLEN, FRAUD ou DAMAGED");
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
