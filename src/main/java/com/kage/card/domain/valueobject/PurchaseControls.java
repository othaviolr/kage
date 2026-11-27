package com.kage.card.domain.valueobject;

import com.kage.card.domain.enums.PurchaseChannel;
import com.kage.shared.domain.exception.BusinessRuleException;

/**
 * Que tipos de compra o cliente deixou ligados no cartão. Tudo vem ligado; desligar é proteção (o
 * cartão fica em casa, a viagem acabou, o plástico vazou).
 *
 * <ul>
 *   <li><b>Online</b>: vale para o número do cartão físico. Os cartões virtuais continuam comprando —
 *       existem justamente para comprar online sem expor o número do físico.</li>
 *   <li><b>Internacional</b>: compra em estabelecimento de fora do Brasil, presencial ou online, com o
 *       físico ou com um virtual.</li>
 *   <li><b>Aproximação</b>: só o físico se aproxima da maquininha.</li>
 * </ul>
 */
public record PurchaseControls(boolean online, boolean international, boolean contactless) {

    public static final PurchaseControls ALL_ALLOWED = new PurchaseControls(true, true, true);

    /** Recusa a compra que o cliente desligou, com o motivo e o que fazer. */
    public void check(PurchaseChannel channel, boolean abroad, boolean virtualCard) {
        if (abroad && !international) {
            throw new BusinessRuleException("Compras internacionais estão desligadas: ligue nas configurações do "
                    + "cartão antes de viajar ou de comprar de fora");
        }
        if (channel == PurchaseChannel.ONLINE && !virtualCard && !online) {
            throw new BusinessRuleException("Compras online com o cartão físico estão desligadas: use um cartão "
                    + "virtual ou ligue nas configurações do cartão");
        }
        if (channel == PurchaseChannel.CONTACTLESS && !contactless) {
            throw new BusinessRuleException("Compras por aproximação estão desligadas: insira o cartão na maquininha "
                    + "ou ligue nas configurações do cartão");
        }
    }

    public PurchaseControls with(Boolean online, Boolean international, Boolean contactless) {
        return new PurchaseControls(online == null ? this.online : online,
                international == null ? this.international : international,
                contactless == null ? this.contactless : contactless);
    }
}
