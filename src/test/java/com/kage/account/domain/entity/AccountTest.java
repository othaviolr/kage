package com.kage.account.domain.entity;

import com.kage.account.domain.enums.AccountStatus;
import com.kage.account.domain.enums.AccountType;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do núcleo de regra de negócio da conta: credit/debit/withdraw e as transições
 * de status. Limites usados nas asserções vêm de Limits.defaultLimits() (conta nova sempre
 * nasce com os limites padrão): dailyTransferLimit = 5000.00, dailyWithdrawalLimit = 1000.00.
 */
class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = Account.create(UUID.randomUUID(), AccountType.CHECKING, "00001", "1");
    }

    @Test
    void credit_deveAumentarSaldoEDisponivel() {
        account.credit(Money.of("100.00"));

        assertThat(account.getBalance().amount()).isEqualByComparingTo("100.00");
        assertThat(account.getAvailableBalance().amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void credit_deveLancarBusinessRuleException_quandoContaEncerrada() {
        account.close(); // saldo zero, pode encerrar direto

        assertThatThrownBy(() -> account.credit(Money.of("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("encerrada");
    }

    @Test
    void debit_deveDiminuirSaldo_quandoDentroDoLimiteESaldoSuficiente() {
        account.credit(Money.of("1000.00"));

        account.debit(Money.of("300.00"));

        assertThat(account.getBalance().amount()).isEqualByComparingTo("700.00");
        assertThat(account.getAvailableBalance().amount()).isEqualByComparingTo("700.00");
    }

    @Test
    void debit_deveLancarBusinessRuleException_quandoSaldoDisponivelInsuficiente() {
        account.credit(Money.of("50.00"));

        assertThatThrownBy(() -> account.debit(Money.of("100.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo disponível insuficiente");
    }

    @Test
    void debit_deveLancarBusinessRuleException_quandoExcedeLimiteDiarioDeTransferencia() {
        account.credit(Money.of("10000.00"));

        assertThatThrownBy(() -> account.debit(Money.of("6000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de transferência");
    }

    @Test
    void withdraw_deveDiminuirSaldo_quandoDentroDoLimite() {
        account.credit(Money.of("500.00"));

        account.withdraw(Money.of("200.00"));

        assertThat(account.getBalance().amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void withdraw_deveLancarBusinessRuleException_quandoSaldoInsuficiente() {
        assertThatThrownBy(() -> account.withdraw(Money.of("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo disponível insuficiente");
    }

    @Test
    void withdraw_deveLancarBusinessRuleException_quandoExcedeLimiteDiarioDeSaque() {
        account.credit(Money.of("5000.00")); // limite padrão de saque é 1000.00

        assertThatThrownBy(() -> account.withdraw(Money.of("1500.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de saque");
    }

    @Test
    void block_deveMudarStatusParaBloqueada() {
        account.block();

        assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
    }

    @Test
    void block_deveLancarBusinessRuleException_quandoJaBloqueada() {
        account.block();

        assertThatThrownBy(() -> account.block())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já está bloqueada");
    }

    @Test
    void close_deveLancarBusinessRuleException_quandoContaTemSaldo() {
        account.credit(Money.of("10.00"));

        assertThatThrownBy(() -> account.close())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("saldo");
    }
}
