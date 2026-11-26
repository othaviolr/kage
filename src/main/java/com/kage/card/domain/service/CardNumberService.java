package com.kage.card.domain.service;

import java.time.YearMonth;
import java.util.UUID;

/**
 * Números do cartão virtual sem guardar o número (PCI DSS: dado sensível que não precisa estar no
 * banco não fica no banco). Número e CVV saem de um segredo do banco: o mesmo cartão gera sempre o
 * mesmo número, e sem o segredo não dá para gerá-lo.
 */
public interface CardNumberService {

    /** Número de 16 dígitos, com dígito verificador de Luhn. */
    String numberFor(UUID virtualCardId);

    String cvvFor(String number, YearMonth expiry);

    /** Para achar o cartão pelo número (UNIQUE no banco) sem guardar o número. */
    String hash(String number);
}
