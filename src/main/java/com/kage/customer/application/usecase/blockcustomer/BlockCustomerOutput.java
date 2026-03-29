package com.kage.customer.application.usecase.blockcustomer;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record BlockCustomerOutput(
        UUID id,
        String status
) {

    public static BlockCustomerOutput from(Customer customer) {
        return new BlockCustomerOutput(
                customer.getId(),
                customer.getStatus().name()
        );
    }
}