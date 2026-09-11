package com.kage.account;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.entity.Account;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de concorrência real do @Version, adiado desde o Passo 1 do plano de resiliência.
 * Em vez de duas threads de verdade (não-determinístico), simula duas requisições concorrentes
 * lendo a MESMA versão da conta e tentando gravar depois — é exatamente o cenário que o
 * @Version existe pra pegar, só que determinístico.
 */
class OptimisticLockingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CreateAccount createAccount;

    @Autowired
    DepositAccount depositAccount;

    @Autowired
    AccountRepository accountRepository;

    @Test
    void save_deveLancarObjectOptimisticLockingFailureException_quandoDuasRequisicoesLeemAMesmaVersao() {
        var created = createAccount.execute(new CreateAccount.Input(UUID.randomUUID(), "CHECKING"));
        depositAccount.execute(new DepositAccount.Input(created.accountId(), new BigDecimal("100.00")));

        // duas "requisições" concorrentes leem a conta antes de qualquer uma delas gravar
        Account requestA = accountRepository.findById(created.accountId()).orElseThrow();
        Account requestB = accountRepository.findById(created.accountId()).orElseThrow();

        requestA.credit(Money.of("10.00"));
        accountRepository.save(requestA); // primeira grava, version incrementa no banco

        requestB.credit(Money.of("20.00")); // ainda carrega a version antiga

        assertThatThrownBy(() -> accountRepository.save(requestB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // efeito da requisição vencedora (A) preservado, B não aplicou nada
        Account finalState = accountRepository.findById(created.accountId()).orElseThrow();
        assertThat(finalState.getBalance().amount()).isEqualByComparingTo("110.00");
    }
}
