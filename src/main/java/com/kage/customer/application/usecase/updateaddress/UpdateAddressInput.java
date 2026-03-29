package com.kage.customer.application.usecase.updateaddress;

import java.util.UUID;

public record UpdateAddressInput(
        UUID id,
        String street,
        String number,
        String complement,
        String city,
        String state,
        String zipCode
) {}