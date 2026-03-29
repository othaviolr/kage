package com.kage.customer.application.usecase.rejectkyc;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record RejectKycOutput(
        UUID id,
        String kycStatus
) {

    public static RejectKycOutput from(Customer customer) {
        return new RejectKycOutput(
                customer.getId(),
                customer.getKycStatus().name()
        );
    }
}