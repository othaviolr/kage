package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.VirtualCard;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.VirtualCardKind;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.VirtualCardRepository;
import com.kage.card.domain.service.CardNumberService;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cartões virtuais de um cartão físico: criar (permanente ou temporário de 24h), ver os dados para
 * comprar online, bloquear/desbloquear e apagar. Compra com o número de um virtual usa o limite e a
 * fatura do físico ({@link #authorize}).
 */
public class VirtualCards {

    /** Virtuais vivos (nem apagados nem expirados) por cartão físico. */
    public static final int MAX_ACTIVE = 10;
    private static final int NUMBER_ATTEMPTS = 3;
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");

    public record Input(UUID cardId, String nickname, String kind) {}

    /** Sem o número completo: é o que aparece na lista. */
    public record Output(UUID virtualCardId, UUID cardId, String nickname, String kind, String maskedNumber,
                         String expiry, String status, LocalDateTime createdAt, LocalDateTime expiresAt,
                         LocalDateTime deletedAt) {}

    /** Os dados para preencher no site. Só para o dono do cartão e só de virtual que ainda vale. */
    public record Details(UUID virtualCardId, String nickname, String kind, String number, String cvv, String expiry,
                          String status, LocalDateTime expiresAt) {}

    private final CardRepository cardRepository;
    private final VirtualCardRepository virtualCardRepository;
    private final CardNumberService numbers;

    public VirtualCards(CardRepository cardRepository, VirtualCardRepository virtualCardRepository,
                        CardNumberService numbers) {
        this.cardRepository = cardRepository;
        this.virtualCardRepository = virtualCardRepository;
        this.numbers = numbers;
    }

    @Transactional
    public Details create(Input input) {
        VirtualCardKind kind = parseKind(input.kind());
        Card card = findCard(input.cardId());
        if (card.getStatus() != CardStatus.ACTIVE) {
            throw new BusinessRuleException("Cartão " + (card.getStatus() == CardStatus.BLOCKED ? "bloqueado" : "cancelado")
                    + ": não dá para criar cartão virtual");
        }
        LocalDateTime now = DomainTime.now();
        long alive = virtualCardRepository.findByCardId(card.getId()).stream()
                .filter(v -> !v.isDeleted() && !v.isExpired(now))
                .count();
        if (alive >= MAX_ACTIVE) {
            throw new BusinessRuleException("Limite de " + MAX_ACTIVE + " cartões virtuais: apague um para criar outro");
        }

        for (int attempt = 1; attempt <= NUMBER_ATTEMPTS; attempt++) {
            UUID id = UUID.randomUUID();
            String number = numbers.numberFor(id);
            VirtualCard virtual = VirtualCard.create(id, card.getId(), input.nickname(), kind,
                    number.substring(number.length() - 4), numbers.hash(number));
            if (virtualCardRepository.insert(virtual)) return details(virtual, number);
            // 1 em 1 bilhão: outro virtual já tem este número; sorteia outro id
        }
        throw new IllegalStateException("Não foi possível gerar um número livre para o cartão virtual");
    }

    public List<Output> list(UUID cardId) {
        findCard(cardId);
        LocalDateTime now = DomainTime.now();
        return virtualCardRepository.findByCardId(cardId).stream().map(v -> toOutput(v, now)).toList();
    }

    public Details details(UUID cardId, UUID virtualCardId) {
        VirtualCard virtual = find(cardId, virtualCardId);
        if (virtual.isDeleted()) throw new BusinessRuleException("Cartão virtual foi apagado");
        if (virtual.isExpired(DomainTime.now())) throw new BusinessRuleException("Cartão virtual expirado");
        return details(virtual, numbers.numberFor(virtual.id()));
    }

    @Transactional
    public Output block(UUID cardId, UUID virtualCardId) {
        return save(find(cardId, virtualCardId).block());
    }

    @Transactional
    public Output unblock(UUID cardId, UUID virtualCardId) {
        return save(find(cardId, virtualCardId).unblock());
    }

    @Transactional
    public Output delete(UUID cardId, UUID virtualCardId) {
        return save(find(cardId, virtualCardId).delete());
    }

    /** Cartão físico cancelado: os virtuais morrem com ele. Chamar na transação do cancelamento. */
    public void deleteAllOf(UUID cardId) {
        virtualCardRepository.findByCardId(cardId).stream()
                .filter(v -> !v.isDeleted())
                .forEach(v -> virtualCardRepository.update(v.delete()));
    }

    /**
     * "Não reconheço" numa compra feita com o virtual: o número vazou, então ele é apagado.
     *
     * @return se apagou (false se já estava apagado)
     */
    public boolean deleteCompromised(UUID virtualCardId) {
        VirtualCard virtual = virtualCardRepository.findById(virtualCardId).orElse(null);
        if (virtual == null || virtual.isDeleted()) return false;
        virtualCardRepository.update(virtual.delete());
        return true;
    }

    /**
     * Compra chegou com o número de um virtual: ele precisa ser deste cartão e estar valendo.
     *
     * @return o virtual (o id fica gravado na linha da fatura; o nome vai no aviso da compra)
     */
    public VirtualCard authorize(UUID cardId, String number) {
        String digits = normalize(number);
        if (!digits.matches("\\d{16}")) throw new ValidationException("Número do cartão virtual deve ter 16 dígitos");
        VirtualCard virtual = virtualCardRepository.findByNumberHash(numbers.hash(digits))
                .filter(v -> v.cardId().equals(cardId))
                .orElseThrow(() -> new NotFoundException("Cartão virtual não encontrado"));
        virtual.requireUsable(DomainTime.now());
        return virtual;
    }

    /** Nome do virtual deste cartão com esse número (para o aviso de compra recusada), sem validar se ele vale. */
    public Optional<String> nicknameOf(UUID cardId, String number) {
        String digits = normalize(number);
        if (!digits.matches("\\d{16}")) return Optional.empty();
        return virtualCardRepository.findByNumberHash(numbers.hash(digits))
                .filter(v -> v.cardId().equals(cardId))
                .map(VirtualCard::nickname);
    }

    private static String normalize(String number) {
        return number == null ? "" : number.replaceAll("[\\s.-]", "");
    }

    private Output save(VirtualCard virtual) {
        virtualCardRepository.update(virtual);
        return toOutput(virtual, DomainTime.now());
    }

    private Details details(VirtualCard v, String number) {
        return new Details(v.id(), v.nickname(), v.kind().name(), number, numbers.cvvFor(number, v.expiry()),
                v.expiry().format(EXPIRY), v.displayStatus(DomainTime.now()), v.expiresAt());
    }

    private static Output toOutput(VirtualCard v, LocalDateTime now) {
        return new Output(v.id(), v.cardId(), v.nickname(), v.kind().name(), "•••• " + v.lastDigits(),
                v.expiry().format(EXPIRY), v.displayStatus(now), v.createdAt(), v.expiresAt(), v.deletedAt());
    }

    private static VirtualCardKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) return VirtualCardKind.PERMANENT;
        try {
            return VirtualCardKind.valueOf(kind.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Tipo inválido: use PERMANENT ou TEMPORARY");
        }
    }

    private VirtualCard find(UUID cardId, UUID virtualCardId) {
        findCard(cardId);
        return virtualCardRepository.findById(virtualCardId)
                .filter(v -> v.cardId().equals(cardId))
                .orElseThrow(() -> new NotFoundException("Cartão virtual não encontrado"));
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
