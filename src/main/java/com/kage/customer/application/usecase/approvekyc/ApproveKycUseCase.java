package com.kage.customer.application.usecase.approvekyc;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

public class ApproveKycUseCase {

    private final CustomerRepository customerRepository;

    public ApproveKycUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public ApproveKycOutput execute(ApproveKycInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        customer.approveKyc();

        var saved = customerRepository.save(customer);

        return ApproveKycOutput.from(saved);
    }
}