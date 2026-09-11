package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fluxo PIX completo de ponta a ponta, com Postgres e RabbitMQ reais: SendPix grava a
 * transação e publica no outbox -> PixOutboxPublisher publica no RabbitMQ -> AccountPixEventConsumer
 * debita a origem e credita o destino (Inbox) -> confirmação volta pro Payment -> PixEventConsumer
 * marca a transação como COMPLETED (Inbox de novo).
 *
 * O mesmo caminho de código atende tanto PIX pra uma conta de outro titular quanto PIX entre
 * duas contas do mesmo titular ("conta própria") — o domínio não distingue os dois casos, só
 * bloqueia especificamente PIX pra própria CONTA (mesma accountId), não pro mesmo titular.
 */
class PixTransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CreateAccount createAccount;

    @Autowired
    DepositAccount depositAccount;

    @Autowired
    RegisterPixKey registerPixKey;

    @Autowired
    SendPix sendPix;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    PixTransactionRepository pixTransactionRepository;

    @Test
    void execute_deveDebitarOrigemECreditarDestino_quandoTransferenciaParaOutroTitular() {
        pixTransferHappyPath(UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void execute_deveDebitarOrigemECreditarDestino_quandoPixEntreContasDoMesmoTitular() {
        UUID customerId = UUID.randomUUID();

        pixTransferHappyPath(customerId, customerId);
    }

    private void pixTransferHappyPath(UUID sourceCustomerId, UUID targetCustomerId) {
        var source = createAccount.execute(new CreateAccount.Input(sourceCustomerId, "CHECKING"));
        var target = createAccount.execute(new CreateAccount.Input(targetCustomerId, "CHECKING"));

        depositAccount.execute(new DepositAccount.Input(source.accountId(), new BigDecimal("500.00")));

        String pixKeyValue = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(target.accountId(), PixKeyType.RANDOM, pixKeyValue));

        SendPix.Output output = sendPix.execute(new SendPix.Input(source.accountId(), pixKeyValue, Money.of("150.00"), "teste de integração"));

        awaitUntil(() -> pixTransactionRepository.findById(output.transactionId())
                .map(transaction -> transaction.getStatus() == PixTransactionStatus.COMPLETED)
                .orElse(false), Duration.ofSeconds(15));

        var sourceAfter = accountRepository.findById(source.accountId()).orElseThrow();
        var targetAfter = accountRepository.findById(target.accountId()).orElseThrow();

        assertThat(sourceAfter.getBalance().amount()).isEqualByComparingTo("350.00");
        assertThat(targetAfter.getBalance().amount()).isEqualByComparingTo("150.00");

        var completedTransaction = pixTransactionRepository.findById(output.transactionId()).orElseThrow();
        assertThat(completedTransaction.getStatus()).isEqualTo(PixTransactionStatus.COMPLETED);
    }
}
