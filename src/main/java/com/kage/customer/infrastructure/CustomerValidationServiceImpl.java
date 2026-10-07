package com.kage.customer.infrastructure;

import com.kage.account.domain.service.AccountCustomerValidationService;
import com.kage.card.domain.service.CardCustomerValidationService;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;

import java.util.UUID;

/**
 * Lado Customer das validações que Account e Card exigem: o cliente existe, teve o KYC aprovado e
 * está ativo (não bloqueado). Uma única regra atende as duas portas.
 */
public class CustomerValidationServiceImpl implements AccountCustomerValidationService, CardCustomerValidationService {

    private final CustomerRepository customerRepository;

    public CustomerValidationServiceImpl(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void validateCanOpenAccount(UUID customerId) {
        requireEligibleCustomer(customerId);
    }

    @Override
    public void validateCanIssueCard(UUID customerId) {
        requireEligibleCustomer(customerId);
    }

    private void requireEligibleCustomer(UUID customerId) {
        var customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new NotFoundException("Cliente não encontrado"));

        if (!customer.isKycApproved()) {
            throw new BusinessRuleException("KYC do cliente não foi aprovado");
        }
        if (!customer.isActive()) {
            throw new BusinessRuleException("Cliente não está ativo");
        }
    }
}
