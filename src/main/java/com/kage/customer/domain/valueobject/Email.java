package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;

public record Email(String value) {

    public Email {
        if (value == null || !isValid(value)) {
            throw new DomainException("Email inválido: " + value);
        }
        value = value.toLowerCase().strip();
    }

    private static boolean isValid(String email) {
        return email != null && email.matches("^[\\w+.\\-]+@[\\w\\-]+\\.[\\w.]{2,}$");
    }

    @Override
    public String toString() {
        return value;
    }
}