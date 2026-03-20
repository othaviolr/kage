package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;

public record Cpf(String value) {

    public Cpf {
        if (value == null || !isValid(value)) {
            throw new DomainException("CPF inválido: " + value);
        }
        value = value.replaceAll("[^0-9]", "");
    }

    private static boolean isValid(String cpf) {
        cpf = cpf.replaceAll("[^0-9]", "");

        if (cpf.length() != 11) return false;
        if (cpf.matches("(\\d)\\1{10}")) return false;

        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (cpf.charAt(i) - '0') * (10 - i);
        int first = 11 - (sum % 11);
        if (first >= 10) first = 0;
        if (first != (cpf.charAt(9) - '0')) return false;

        sum = 0;
        for (int i = 0; i < 10; i++) sum += (cpf.charAt(i) - '0') * (11 - i);
        int second = 11 - (sum % 11);
        if (second >= 10) second = 0;

        return second == (cpf.charAt(10) - '0');
    }

    @Override
    public String toString() {
        return value.replaceAll("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4");
    }
}