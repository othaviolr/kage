package com.kage.customer.application.usecase.updateaddress;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record UpdateAddressOutput(
        UUID id,
        String street,
        String city,
        String state,
        String zipCode
) {

    public static UpdateAddressOutput from(Customer customer) {
        return new UpdateAddressOutput(
                customer.getId(),
                customer.getAddress().street(),
                customer.getAddress().city(),
                customer.getAddress().state(),
                customer.getAddress().zipCode()
        );
    }
}