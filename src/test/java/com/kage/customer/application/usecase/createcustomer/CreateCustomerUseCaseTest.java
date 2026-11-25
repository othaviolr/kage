package com.kage.customer.application.usecase.createcustomer;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.shared.domain.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A duplicidade de CPF/email precisa ser checada pelo valor normalizado, que é o que fica salvo no
 * banco: "529.982.247-25" e "52998224725" são o mesmo CPF.
 */
@ExtendWith(MockitoExtension.class)
class CreateCustomerUseCaseTest {

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    CreateCustomerUseCase createCustomer;

    private CreateCustomerInput input(String cpf, String email) {
        return new CreateCustomerInput("Fulano de Tal", cpf, email, "(41) 99999-1234", LocalDate.of(1990, 5, 20),
                "Rua das Flores", "100", null, "Curitiba", "PR", "80000-000");
    }

    @Test
    void execute_deveLancarConflictException_quandoCpfFormatadoJaExiste() {
        when(customerRepository.existsByCpf("52998224725")).thenReturn(true);

        assertThatThrownBy(() -> createCustomer.execute(input("529.982.247-25", "fulano@teste.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("CPF já cadastrado");
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void execute_deveLancarConflictException_quandoEmailComMaiusculasJaExiste() {
        when(customerRepository.existsByCpf("52998224725")).thenReturn(false);
        when(customerRepository.existsByEmail("fulano@teste.com")).thenReturn(true);

        assertThatThrownBy(() -> createCustomer.execute(input("529.982.247-25", "Fulano@Teste.com")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Email já cadastrado");
        verify(customerRepository, never()).save(any(Customer.class));
    }
}
