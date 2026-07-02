package com.kage.customer.application.usecase.rejectkyc;

import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;

public class RejectKycUseCase {

    private final CustomerRepository customerRepository;

    public RejectKycUseCase(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public RejectKycOutput execute(RejectKycInput input) {
        var customer = customerRepository.findById(input.id())
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        customer.rejectKyc();

        var saved = customerRepository.save(customer);

        return RejectKycOutput.from(saved);
    }
}