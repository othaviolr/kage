package com.kage.customer.infrastructure;

import com.kage.customer.domain.entity.Customer;
import com.kage.customer.domain.repository.CustomerRepository;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.Cpf;
import com.kage.customer.domain.valueobject.Email;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.customer.domain.valueobject.Phone;
import com.kage.shared.domain.exception.BusinessRuleException;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Regra do lado Customer usada por Account (abrir conta) e Card (emitir cartão): o cliente existe,
 * teve o KYC aprovado e está ativo. Cada cenário roda pelas duas portas.
 */
@ExtendWith(MockitoExtension.class)
class CustomerValidationServiceImplTest {

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    CustomerValidationServiceImpl validationService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = Customer.create(
                new PersonalInfo("Fulano de Tal", new Cpf("529.982.247-25"), new Email("fulano@teste.com"),
                        new Phone("(41) 99999-1234"), LocalDate.of(1990, 5, 20)),
                new Address("Rua das Flores", "100", null, "Curitiba", "PR", "80000-000"));
    }

    private void stubFound() {
        when(customerRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
    }

    @Test
    void deveAceitarCliente_quandoKycAprovadoEAtivo() {
        customer.approveKyc();
        stubFound();

        assertThatCode(() -> validationService.validateCanOpenAccount(customer.getId())).doesNotThrowAnyException();
        assertThatCode(() -> validationService.validateCanIssueCard(customer.getId())).doesNotThrowAnyException();
    }

    @Test
    void deveLancarNotFoundException_quandoClienteNaoExiste() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validationService.validateCanOpenAccount(id))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cliente não encontrado");
        assertThatThrownBy(() -> validationService.validateCanIssueCard(id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deveLancarBusinessRuleException_quandoKycPendente() {
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanOpenAccount(customer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");
        assertThatThrownBy(() -> validationService.validateCanIssueCard(customer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");
    }

    @Test
    void deveLancarBusinessRuleException_quandoKycRejeitado() {
        customer.rejectKyc();
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanOpenAccount(customer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");
    }

    @Test
    void deveLancarBusinessRuleException_quandoClienteBloqueado() {
        customer.approveKyc();
        customer.block();
        stubFound();

        assertThatThrownBy(() -> validationService.validateCanOpenAccount(customer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
        assertThatThrownBy(() -> validationService.validateCanIssueCard(customer.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }
}
