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
 * nasce com os limites padrão): dailyWithdrawalLimit = 1000.00, pixDailyLimit = 2000.00,
 * pixNightLimit = 1000.00.
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
                .hasMessageContaining("não está ativa");
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
    void debit_naoAplicaLimiteDeCanal_soSaldo() {
        // limites dependem do canal (PIX, saque) e são checados antes, com o acumulado do período
        account.credit(Money.of("10000.00"));

        account.debit(Money.of("6000.00"));

        assertThat(account.getBalance().amount()).isEqualByComparingTo("4000.00");
    }

    @Test
    void debit_deveLancarBusinessRuleException_quandoContaBloqueada() {
        account.credit(Money.of("100.00"));
        account.block();

        assertThatThrownBy(() -> account.debit(Money.of("10.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativa");
    }

    @Test
    void withdraw_deveDiminuirSaldo_quandoDentroDoLimite() {
        account.credit(Money.of("500.00"));

        account.withdraw(Money.of("200.00"), Money.ZERO);

        assertThat(account.getBalance().amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void withdraw_deveLancarBusinessRuleException_quandoSaldoInsuficiente() {
        assertThatThrownBy(() -> account.withdraw(Money.of("10.00"), Money.ZERO))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo disponível insuficiente");
    }

    @Test
    void withdraw_deveLancarBusinessRuleException_quandoExcedeLimiteDiarioDeSaque() {
        account.credit(Money.of("5000.00")); // limite padrão de saque é 1000.00

        assertThatThrownBy(() -> account.withdraw(Money.of("1500.00"), Money.ZERO))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de saque");
    }

    @Test
    void withdraw_deveSomarSaquesDoDia_quandoVariosSaquesPequenosEstouramOLimite() {
        account.credit(Money.of("5000.00"));

        // 800 já sacados hoje + 300 agora = 1100 > 1000: antes passava, porque o limite era por saque
        assertThatThrownBy(() -> account.withdraw(Money.of("300.00"), Money.of("800.00")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("disponível hoje: 200.00");
        assertThat(account.getBalance().amount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void withdraw_devePermitir_quandoAcumuladoFechaExatamenteNoLimite() {
        account.credit(Money.of("5000.00"));

        account.withdraw(Money.of("200.00"), Money.of("800.00"));

        assertThat(account.getBalance().amount()).isEqualByComparingTo("4800.00");
    }

    // limites padrão de PIX: diário 2000.00, noturno 1000.00

    @Test
    void checkPixLimits_devePassar_quandoDentroDosLimites() {
        account.credit(Money.of("5000.00"));

        account.checkPixLimits(Money.of("500.00"), Money.of("1500.00"), Money.ZERO, false);
    }

    @Test
    void checkPixLimits_deveSomarPixDoDia() {
        account.credit(Money.of("5000.00"));

        assertThatThrownBy(() -> account.checkPixLimits(Money.of("600.00"), Money.of("1500.00"), Money.ZERO, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite diário de PIX (disponível hoje: 500.00)");
    }

    @Test
    void checkPixLimits_deveAplicarLimiteNoturno_soNaJanelaNoturna() {
        account.credit(Money.of("5000.00"));

        account.checkPixLimits(Money.of("1200.00"), Money.ZERO, Money.ZERO, false);

        assertThatThrownBy(() -> account.checkPixLimits(Money.of("1200.00"), Money.ZERO, Money.ZERO, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite noturno de PIX");
    }

    @Test
    void checkPixLimits_deveSomarPixDaNoite() {
        account.credit(Money.of("5000.00"));

        assertThatThrownBy(() -> account.checkPixLimits(Money.of("300.00"), Money.of("800.00"), Money.of("800.00"), true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("disponível até as 6h: 200.00");
    }

    @Test
    void checkPixLimits_deveRecusar_quandoSaldoInsuficiente() {
        account.credit(Money.of("100.00"));

        assertThatThrownBy(() -> account.checkPixLimits(Money.of("150.00"), Money.ZERO, Money.ZERO, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Saldo insuficiente");
    }

    @Test
    void checkPixLimits_deveRecusar_quandoContaBloqueada() {
        account.credit(Money.of("100.00"));
        account.block();

        assertThatThrownBy(() -> account.checkPixLimits(Money.of("10.00"), Money.ZERO, Money.ZERO, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está ativa");
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
