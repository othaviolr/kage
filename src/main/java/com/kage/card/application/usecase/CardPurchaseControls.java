package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.enums.CardStatus;
import com.kage.card.domain.enums.PurchaseChannel;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.PurchaseControlsRepository;
import com.kage.card.domain.valueobject.PurchaseControls;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** O cliente liga e desliga compras online, internacionais e por aproximação ({@link PurchaseControls}). */
public class CardPurchaseControls {

    /** @param notes o que cada escolha significa, para a tela de configurações */
    public record View(UUID cardId, boolean online, boolean international, boolean contactless, List<String> notes) {}

    private final CardRepository cardRepository;
    private final PurchaseControlsRepository repository;

    public CardPurchaseControls(CardRepository cardRepository, PurchaseControlsRepository repository) {
        this.cardRepository = cardRepository;
        this.repository = repository;
    }

    public View get(UUID cardId) {
        findCard(cardId);
        return view(cardId, controlsOf(cardId));
    }

    /** Campo nulo = não muda. */
    @Transactional
    public View update(UUID cardId, Boolean online, Boolean international, Boolean contactless) {
        Card card = findCard(cardId);
        if (online == null && international == null && contactless == null) {
            throw new ValidationException("Informe ao menos um: online, international ou contactless");
        }
        if (card.getStatus() == CardStatus.CANCELLED) throw new BusinessRuleException("Cartão cancelado");
        PurchaseControls updated = controlsOf(cardId).with(online, international, contactless);
        repository.save(cardId, updated, DomainTime.now());
        return view(cardId, updated);
    }

    /** Chamado na autorização da compra: recusa o que o cliente desligou. */
    public void authorize(UUID cardId, PurchaseChannel channel, boolean abroad, boolean virtualCard) {
        controlsOf(cardId).check(channel, abroad, virtualCard);
    }

    private PurchaseControls controlsOf(UUID cardId) {
        return repository.find(cardId).orElse(PurchaseControls.ALL_ALLOWED);
    }

    private static View view(UUID cardId, PurchaseControls c) {
        List<String> notes = new ArrayList<>();
        notes.add(c.online()
                ? "Compras online: ligadas, com o cartão físico e com os virtuais."
                : "Compras online com o cartão físico: desligadas. Os cartões virtuais continuam comprando online.");
        notes.add(c.international()
                ? "Compras internacionais: ligadas."
                : "Compras internacionais: desligadas, presenciais e online, com qualquer cartão.");
        notes.add(c.contactless()
                ? "Compras por aproximação: ligadas."
                : "Compras por aproximação: desligadas. Inserindo o cartão na maquininha, a compra passa.");
        return new View(cardId, c.online(), c.international(), c.contactless(), notes);
    }

    private Card findCard(UUID cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new NotFoundException("Cartão não encontrado"));
    }
}
