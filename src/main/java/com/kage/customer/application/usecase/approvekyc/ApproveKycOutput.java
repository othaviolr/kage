package com.kage.customer.application.usecase.approvekyc;

import com.kage.customer.domain.entity.Customer;

import java.util.UUID;

public record ApproveKycOutput(
        UUID id,
        String kycStatus,
        String status
) {

    public static ApproveKycOutput from(Customer customer) {
        return new ApproveKycOutput(
                customer.getId(),
                customer.getKycStatus().name(),
                customer.getStatus().name()
        );
    }
}