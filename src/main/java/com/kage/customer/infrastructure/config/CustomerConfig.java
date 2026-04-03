package com.kage.customer.infrastructure.config;

import com.kage.customer.application.usecase.approvekyc.ApproveKycUseCase;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerUseCase;
import com.kage.customer.application.usecase.createcustomer.CreateCustomerUseCase;
import com.kage.customer.application.usecase.getcustomer.GetCustomerUseCase;
import com.kage.customer.application.usecase.rejectkyc.RejectKycUseCase;
import com.kage.customer.application.usecase.updateaddress.UpdateAddressUseCase;
import com.kage.customer.domain.repository.CustomerRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerConfig {

    @Bean
    public CreateCustomerUseCase createCustomerUseCase(CustomerRepository customerRepository) {
        return new CreateCustomerUseCase(customerRepository);
    }

    @Bean
    public GetCustomerUseCase getCustomerUseCase(CustomerRepository customerRepository) {
        return new GetCustomerUseCase(customerRepository);
    }

    @Bean
    public BlockCustomerUseCase blockCustomerUseCase(CustomerRepository customerRepository) {
        return new BlockCustomerUseCase(customerRepository);
    }

    @Bean
    public ApproveKycUseCase approveKycUseCase(CustomerRepository customerRepository) {
        return new ApproveKycUseCase(customerRepository);
    }

    @Bean
    public RejectKycUseCase rejectKycUseCase(CustomerRepository customerRepository) {
        return new RejectKycUseCase(customerRepository);
    }

    @Bean
    public UpdateAddressUseCase updateAddressUseCase(CustomerRepository customerRepository) {
        return new UpdateAddressUseCase(customerRepository);
    }
}