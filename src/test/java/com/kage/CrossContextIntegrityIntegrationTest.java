package com.kage;

import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.BlockAccount;
import com.kage.card.application.usecase.IssueCard;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerInput;
import com.kage.customer.application.usecase.blockcustomer.BlockCustomerUseCase;
import com.kage.customer.application.usecase.unblockcustomer.UnblockCustomerInput;
import com.kage.customer.application.usecase.unblockcustomer.UnblockCustomerUseCase;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integridade entre contextos com Postgres real: conta e cartão só nascem para cliente existente,
 * com KYC aprovado e ativo, e o cartão só vincula uma conta ativa que pertence a esse cliente.
 */
class CrossContextIntegrityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    BlockCustomerUseCase blockCustomer;

    @Autowired
    UnblockCustomerUseCase unblockCustomer;

    @Autowired
    CreateAccount createAccount;

    @Autowired
    BlockAccount blockAccount;

    @Autowired
    IssueCard issueCard;

    // ---------- CreateAccount ----------

    @Test
    void createAccount_deveAbrirConta_quandoClienteAtivoComKycAprovado() {
        UUID customerId = newActiveCustomerId();

        var account = createAccount.execute(new CreateAccount.Input(customerId, "CHECKING"));

        assertThat(account.status()).isEqualTo("ACTIVE");
    }

    @Test
    void createAccount_deveLancarNotFoundException_quandoClienteNaoExiste() {
        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(UUID.randomUUID(), "CHECKING")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cliente não encontrado");
    }

    @Test
    void createAccount_deveLancarBusinessRuleException_quandoKycPendente() {
        UUID customerId = newPendingCustomerId();

        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");
    }

    @Test
    void createAccount_deveLancarBusinessRuleException_quandoClienteBloqueado_eVoltarAAceitarAposDesbloqueio() {
        UUID customerId = newActiveCustomerId();
        blockCustomer.execute(new BlockCustomerInput(customerId));

        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");

        unblockCustomer.execute(new UnblockCustomerInput(customerId));

        assertThat(createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")).status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void createAccount_deveLancarBusinessRuleException_quandoClienteBloqueadoAntesDoKyc_eDesbloqueioNaoAtiva() {
        UUID customerId = newPendingCustomerId();
        blockCustomer.execute(new BlockCustomerInput(customerId));
        unblockCustomer.execute(new UnblockCustomerInput(customerId));

        assertThatThrownBy(() -> createAccount.execute(new CreateAccount.Input(customerId, "CHECKING")))
                .isInstanceOf(BusinessRuleException.class);
    }

    // ---------- IssueCard ----------

    @Test
    void issueCard_deveEmitirCartao_quandoContaAtivaEDoCliente() {
        var card = issueCard.execute(issueCardInput(new BigDecimal("1000.00"), 10, 20));

        assertThat(card.status()).isEqualTo("ACTIVE");
    }

    @Test
    void issueCard_deveLancarNotFoundException_quandoClienteNaoExiste() {
        UUID accountId = newAccountIdFor(newActiveCustomerId());

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                UUID.randomUUID(), accountId, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cliente não encontrado");
    }

    @Test
    void issueCard_deveLancarNotFoundException_quandoContaNaoExiste() {
        UUID customerId = newActiveCustomerId();

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, UUID.randomUUID(), new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Conta não encontrada");
    }

    @Test
    void issueCard_deveLancarBusinessRuleException_quandoClienteBloqueado() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        blockCustomer.execute(new BlockCustomerInput(customerId));

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, accountId, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativo");
    }

    @Test
    void issueCard_deveLancarBusinessRuleException_quandoKycPendente() {
        UUID customerId = newPendingCustomerId();
        UUID accountOfOther = newAccountIdFor(newActiveCustomerId());

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, accountOfOther, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("KYC");
    }

    @Test
    void issueCard_deveLancarBusinessRuleException_quandoContaEDeOutroCliente() {
        UUID customerId = newActiveCustomerId();
        UUID accountOfOther = newAccountIdFor(newActiveCustomerId());

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, accountOfOther, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pertence ao cliente");
    }

    @Test
    void issueCard_deveLancarBusinessRuleException_quandoContaBloqueada() {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        blockAccount.execute(new BlockAccount.Input(accountId));

        assertThatThrownBy(() -> issueCard.execute(new IssueCard.Input(
                customerId, accountId, new BigDecimal("1000.00"), 10, 20)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativa");
    }
}
