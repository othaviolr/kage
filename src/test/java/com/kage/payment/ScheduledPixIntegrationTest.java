package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.payment.application.usecase.ManageScheduledPix;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.RunScheduledPix;
import com.kage.payment.application.usecase.SchedulePix;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.payment.domain.enums.PixTransactionStatus;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PIX agendado com Postgres e RabbitMQ reais. A rodada é disparada pelo use case com uma data
 * escolhida (o mesmo que o job das 06:00 faz com a data de hoje).
 */
class ScheduledPixIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);

    @Autowired DepositAccount depositAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired SchedulePix schedulePix;
    @Autowired SendPix sendPix;
    @Autowired ManageScheduledPix manageScheduledPix;
    @Autowired RunScheduledPix runScheduledPix;
    @Autowired PixTransactionRepository pixTransactionRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired ListNotifications listNotifications;

    private record Parties(UUID payer, UUID payerAccount, UUID receiverAccount, String key) {}

    private Parties parties(String payerBalance) {
        UUID payer = newActiveCustomerId();
        UUID payerAccount = newAccountIdFor(payer);
        UUID receiverAccount = newAccountIdFor(newActiveCustomerId());
        if (new BigDecimal(payerBalance).signum() > 0) {
            depositAccount.execute(new DepositAccount.Input(payerAccount, new BigDecimal(payerBalance)));
        }
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiverAccount, PixKeyType.RANDOM, key));
        return new Parties(payer, payerAccount, receiverAccount, key);
    }

    private UUID schedule(Parties p, String amount) {
        return schedulePix.execute(new SchedulePix.Input(p.payerAccount(), p.key(), Money.of(amount), "aluguel", DAY)).transactionId();
    }

    private PixTransactionStatus statusOf(UUID id) {
        return pixTransactionRepository.findById(id).orElseThrow().getStatus();
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    @Test
    void deveSerEnviadoSoNoDia_eUmaVezSo() {
        Parties p = parties("500.00");
        UUID id = schedule(p, "200.00");
        assertThat(manageScheduledPix.listPending(p.payerAccount())).extracting(SchedulePix.Output::transactionId).contains(id);

        runScheduledPix.execute(DAY.minusDays(1));
        assertThat(statusOf(id)).isEqualTo(PixTransactionStatus.PENDING);

        runScheduledPix.execute(DAY);
        awaitUntil(() -> statusOf(id) == PixTransactionStatus.COMPLETED, Duration.ofSeconds(15));
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("300.00");
        assertThat(balanceOf(p.receiverAccount())).isEqualByComparingTo("200.00");

        runScheduledPix.execute(DAY); // de novo no mesmo dia
        sleep(Duration.ofSeconds(2));
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("300.00");
    }

    @Test
    void semSaldoNoDia_deveFalhar_eAvisarOCliente() {
        Parties p = parties("0");
        UUID id = schedule(p, "200.00");

        runScheduledPix.execute(DAY);

        assertThat(statusOf(id)).isEqualTo(PixTransactionStatus.FAILED);
        assertThat(pixTransactionRepository.findById(id).orElseThrow().getFailureReason()).contains("Saldo");
        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(p.payer(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals("PIX_SCHEDULED_FAILED")), Duration.ofSeconds(15));
    }

    @Test
    void cancelado_naoDeveSerEnviado() {
        Parties p = parties("500.00");
        UUID id = schedule(p, "200.00");

        manageScheduledPix.cancel(id);
        runScheduledPix.execute(DAY);

        assertThat(statusOf(id)).isEqualTo(PixTransactionStatus.CANCELLED);
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("500.00");
    }

    @Test
    void agendar_naoDeveConsumirOLimiteDeHoje() {
        // limites padrão: diário 2000, noturno 1000. Antes da correção, o agendamento de 1500 contava
        // como "enviado hoje" e o PIX imediato de 900 (1500 + 900 > 2000) seria recusado. 900 fica
        // abaixo do limite noturno, então o teste vale a qualquer hora do dia.
        Parties p = parties("5000.00");
        schedule(p, "1500.00");

        SendPix.Output now = sendPix.execute(new SendPix.Input(p.payerAccount(), p.key(), Money.of("900.00"), "agora"));

        assertThat(now.status()).isEqualTo("PROCESSING");
    }
}
