package com.kage.customer.application.usecase.rejectkyc;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;

public class RejectKycUseCase {

    private final CustomerRepository customerRepository;

    public RejectKycUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public RejectKycOutput execute(RejectKycInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new DomainException("Cliente não encontrado"));

        customer.rejectKyc();

        var saved = customerRepository.save(customer);

        return RejectKycOutput.from(saved);
    }
}