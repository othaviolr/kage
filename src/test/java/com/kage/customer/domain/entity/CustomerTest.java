package com.kage.customer.domain.entity;

import com.kage.customer.domain.enums.CustomerStatus;
import com.kage.customer.domain.enums.KycStatus;
import com.kage.customer.domain.valueobject.Address;
import com.kage.customer.domain.valueobject.Cpf;
import com.kage.customer.domain.valueobject.Email;
import com.kage.customer.domain.valueobject.PersonalInfo;
import com.kage.customer.domain.valueobject.Phone;
import com.kage.shared.domain.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do ciclo de vida do cliente: KYC, bloqueio e desbloqueio. O cliente nasce INACTIVE com KYC
 * PENDING e só vira ACTIVE quando o KYC é aprovado.
 */
class CustomerTest {

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = Customer.create(
                new PersonalInfo("Fulano de Tal", new Cpf("529.982.247-25"), new Email("fulano@teste.com"),
                        new Phone("(41) 99999-1234"), LocalDate.of(1990, 5, 20)),
                new Address("Rua das Flores", "100", null, "Curitiba", "PR", "80000-000"));
    }

    @Test
    void create_deveNascerInativoComKycPendente() {
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.PENDING);
    }

    @Test
    void approveKyc_deveAtivarOCliente() {
        customer.approveKyc();

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.APPROVED);
        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void approveKyc_deveLancarDomainException_quandoJaAprovado() {
        customer.approveKyc();

        assertThatThrownBy(() -> customer.approveKyc())
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("KYC já foi aprovado");
    }

    @Test
    void rejectKyc_deveLancarDomainException_quandoJaAprovado() {
        customer.approveKyc();

        assertThatThrownBy(() -> customer.rejectKyc())
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("já aprovado");
    }

    @Test
    void block_deveMudarStatusParaBloqueado() {
        customer.approveKyc();

        customer.block();

        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.BLOCKED);
    }

    @Test
    void block_deveLancarDomainException_quandoJaBloqueado() {
        customer.block();

        assertThatThrownBy(() -> customer.block())
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("já está bloqueado");
    }

    @Test
    void unblock_deveVoltarParaAtivo_quandoKycAprovado() {
        customer.approveKyc();
        customer.block();

        customer.unblock();

        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(customer.isActive()).isTrue();
    }

    @Test
    void unblock_deveVoltarParaInativo_quandoKycNaoFoiAprovado() {
        customer.block();

        customer.unblock();

        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(customer.isActive()).isFalse();
    }

    @Test
    void unblock_deveManterKycRejeitadoSemAtivar() {
        customer.rejectKyc();
        customer.block();

        customer.unblock();

        assertThat(customer.getStatus()).isEqualTo(CustomerStatus.INACTIVE);
        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.REJECTED);
    }

    @Test
    void unblock_deveLancarDomainException_quandoNaoEstaBloqueado() {
        customer.approveKyc();

        assertThatThrownBy(() -> customer.unblock())
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("não está bloqueado");
    }
}
