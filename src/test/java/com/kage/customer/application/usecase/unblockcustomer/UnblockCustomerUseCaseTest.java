package com.kage.customer.application.usecase.unblockcustomer;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.enums.CustomerStatus;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.Cpf;
import com.kage.customer.domain.valueobject.Email;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.customer.domain.valueobject.Phone;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnblockCustomerUseCaseTest {

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    UnblockCustomerUseCase unblockCustomer;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = Customer.create(
                new PersonalInfo("Fulano de Tal", new Cpf("529.982.247-25"), new Email("fulano@teste.com"),
                        new Phone("(41) 99999-1234"), LocalDate.of(1990, 5, 20)),
                new Address("Rua das Flores", "100", null, "Curitiba", "PR", "80000-000"));
        customer.approveKyc();
    }

    @Test
    void execute_deveDesbloquearClienteBloqueado() {
        customer.block();
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UnblockCustomerOutput output = unblockCustomer.execute(new UnblockCustomerInput(customer.getId()));

        assertThat(output.id()).isEqualTo(customer.getId());
        assertThat(output.status()).isEqualTo(CustomerStatus.ACTIVE.name());
    }

    @Test
    void execute_deveLancarNotFoundException_quandoClienteNaoExiste() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> unblockCustomer.execute(new UnblockCustomerInput(id)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cliente não encontrado");
    }

    @Test
    void execute_devePropagarDomainException_quandoClienteNaoEstaBloqueado() {
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> unblockCustomer.execute(new UnblockCustomerInput(customer.getId())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("não está bloqueado");
        verify(customerRepository, never()).save(any());
    }
}
