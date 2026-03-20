package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;

public record Phone(String value) {

    public Phone {
        if (value == null || !isValid(value)) {
            throw new DomainException("Telefone inválido: " + value);
        }
        value = value.replaceAll("[^0-9]", "");
    }

    private static boolean isValid(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() == 10 || digits.length() == 11;
    }

    @Override
    public String toString() {
        if (value.length() == 11) {
            return value.replaceAll("(\\d{2})(\\d{5})(\\d{4})", "($1) $2-$3");
        }
        return value.replaceAll("(\\d{2})(\\d{4})(\\d{4})", "($1) $2-$3");
    }
}