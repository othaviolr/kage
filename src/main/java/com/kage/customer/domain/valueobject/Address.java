package com.kage.customer.domain.valueobject;

import com.kage.shared.domain.exception.DomainException;

public record Address(
        String street,
        String number,
        String complement,
        String city,
        String state,
        String zipCode
) {

    public Address {
        if (street == null || street.isBlank()) throw new DomainException("Rua é obrigatória");
        if (number == null || number.isBlank()) throw new DomainException("Número é obrigatório");
        if (city == null || city.isBlank()) throw new DomainException("Cidade é obrigatória");
        if (state == null || state.isBlank()) throw new DomainException("Estado é obrigatório");
        if (zipCode == null || !isValidZipCode(zipCode)) throw new DomainException("CEP inválido: " + zipCode);

        zipCode = zipCode.replaceAll("[^0-9]", "");
        complement = (complement == null) ? "" : complement.strip();
    }

    private static boolean isValidZipCode(String zipCode) {
        return zipCode != null && zipCode.replaceAll("[^0-9]", "").length() == 8;
    }
}