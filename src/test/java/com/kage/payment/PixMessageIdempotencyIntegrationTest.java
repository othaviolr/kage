package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.messaging.PixSentEvent;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova o Inbox pattern com reentrega de mensagem de verdade (não mockada): publica a MESMA
 * PixSentEvent duas vezes na pix.sent.queue, exatamente como o RabbitMQ faria numa reentrega
 * (ex: ack perdido por queda da app entre processar e confirmar), e garante que o
 * AccountPixEventConsumer só aplica o débito/crédito uma vez.
 *
 * Publica direto pelo RabbitTemplate em vez de passar pelo fluxo completo do SendPix — assim
 * o teste foca só na idempotência do consumer, sem depender do outbox publisher no meio.
 */
class PixMessageIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CreateAccount createAccount;

    @Autowired
    DepositAccount depositAccount;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    PixTransactionRepository pixTransactionRepository;

    @Test
    void onPixSent_deveDebitarECreditarApenasUmaVez_quandoMensagemEhReentregue() {
        var source = createAccount.execute(new CreateAccount.Input(newActiveCustomerId(), "CHECKING"));
        var target = createAccount.execute(new CreateAccount.Input(newActiveCustomerId(), "CHECKING"));
        depositAccount.execute(new DepositAccount.Input(source.accountId(), new BigDecimal("500.00")));

        // a transação existe de verdade: a confirmação do débito volta para o Payment, que precisa
        // achá-la (com id aleatório, a confirmação esgotava os retries e sujava o log da suíte)
        PixTransaction transaction = PixTransaction.create(source.accountId(), "chave-teste", Money.of("100.00"),
                null, TransactionType.IMMEDIATE, null);
        transaction.startProcessing(target.accountId());
        pixTransactionRepository.save(transaction);

        PixSentEvent event = new PixSentEvent(transaction.getTransactionId(), source.accountId(), "chave-teste",
                target.accountId(), new BigDecimal("100.00"), "PROCESSING", transaction.getE2eId(),
                LocalDateTime.now().toString());

        rabbitTemplate.convertAndSend("pix.exchange", "pix.sent", event);

        awaitUntil(() -> accountRepository.findById(source.accountId())
                .map(account -> account.getBalance().amount().compareTo(new BigDecimal("400.00")) == 0)
                .orElse(false), Duration.ofSeconds(15));

        // reentrega da MESMA mensagem (mesmo transactionId) — simula o RabbitMQ redelivering
        rabbitTemplate.convertAndSend("pix.exchange", "pix.sent", event);

        sleep(Duration.ofSeconds(3)); // dá tempo do consumer processar (ou ignorar) a reentrega

        var sourceAfter = accountRepository.findById(source.accountId()).orElseThrow();
        var targetAfter = accountRepository.findById(target.accountId()).orElseThrow();

        assertThat(sourceAfter.getBalance().amount()).isEqualByComparingTo("400.00");
        assertThat(targetAfter.getBalance().amount()).isEqualByComparingTo("100.00");
        awaitUntil(() -> pixTransactionRepository.findById(transaction.getTransactionId()).orElseThrow().getStatus()
                == PixTransactionStatus.COMPLETED, Duration.ofSeconds(15));
    }
}
