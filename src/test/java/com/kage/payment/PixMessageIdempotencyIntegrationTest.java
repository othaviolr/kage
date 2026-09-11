package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.CreateAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.account.infrastructure.messaging.PixSentEvent;
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

    @Test
    void onPixSent_deveDebitarECreditarApenasUmaVez_quandoMensagemEhReentregue() {
        var source = createAccount.execute(new CreateAccount.Input(UUID.randomUUID(), "CHECKING"));
        var target = createAccount.execute(new CreateAccount.Input(UUID.randomUUID(), "CHECKING"));
        depositAccount.execute(new DepositAccount.Input(source.accountId(), new BigDecimal("500.00")));

        PixSentEvent event = new PixSentEvent(UUID.randomUUID(), source.accountId(), "chave-teste",
                target.accountId(), new BigDecimal("100.00"), "PROCESSING", "E2ETESTE123", LocalDateTime.now().toString());

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
    }
}
