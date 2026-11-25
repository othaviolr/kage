package com.kage.account.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

/**
 * Dígito verificador módulo 11 (pesos 2 a 9 da direita para a esquerda), o esquema usado pela
 * maioria dos bancos brasileiros. Pega erro de digitação de um dígito e a troca de dois vizinhos —
 * a soma simples dos dígitos não pegava a troca.
 */
public final class AccountCheckDigit {

    private AccountCheckDigit() {
    }

    public static String of(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("\\d+")) {
            throw new ValidationException("Número de conta inválido: " + accountNumber);
        }

        int sum = 0;
        int weight = 2;
        for (int i = accountNumber.length() - 1; i >= 0; i--) {
            sum += (accountNumber.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }

        int digit = 11 - (sum % 11);
        return String.valueOf(digit >= 10 ? 0 : digit);
    }
}
