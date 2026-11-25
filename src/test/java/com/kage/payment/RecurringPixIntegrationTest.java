package com.kage.payment;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.payment.application.usecase.ManageScheduledPix;
import com.kage.payment.application.usecase.RecurringPix;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.RunScheduledPix;
import com.kage.payment.application.usecase.SchedulePix;
import com.kage.payment.domain.entity.PixRecurrence.Frequency;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PIX recorrente com Postgres e RabbitMQ reais. As rodadas são disparadas pelo use case com a data
 * de cada ocorrência (o mesmo que o job diário faz com a data de hoje).
 */
class RecurringPixIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate START = LocalDate.now().plusDays(3);

    @Autowired DepositAccount depositAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired RecurringPix recurringPix;
    @Autowired ManageScheduledPix manageScheduledPix;
    @Autowired RunScheduledPix runScheduledPix;
    @Autowired AccountRepository accountRepository;

    private record Parties(UUID payerAccount, UUID receiverAccount, String key) {}

    private Parties parties(String payerBalance) {
        UUID payerAccount = newAccountIdFor(newActiveCustomerId());
        UUID receiverAccount = newAccountIdFor(newActiveCustomerId());
        if (new BigDecimal(payerBalance).signum() > 0) {
            depositAccount.execute(new DepositAccount.Input(payerAccount, new BigDecimal(payerBalance)));
        }
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(receiverAccount, PixKeyType.RANDOM, key));
        return new Parties(payerAccount, receiverAccount, key);
    }

    private RecurringPix.View monthly(Parties p, String amount, int occurrences) {
        return recurringPix.create(new RecurringPix.Input(p.payerAccount(), p.key(), Money.of(amount), "aluguel",
                Frequency.MONTHLY, START, occurrences));
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance().amount();
    }

    private void awaitBalance(UUID accountId, String expected) {
        awaitUntil(() -> balanceOf(accountId).compareTo(new BigDecimal(expected)) == 0, Duration.ofSeconds(15));
    }

    @Test
    void mensal3x_deveEnviarUmaPorMes_eEncerrar() {
        Parties p = parties("1000.00");
        RecurringPix.View created = monthly(p, "200.00", 3);

        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.nextDate()).isEqualTo(START);
        assertThat(created.remaining()).isEqualTo(3);
        assertThat(created.occurrences()).hasSize(1); // só a próxima existe
        assertThat(manageScheduledPix.listPending(p.payerAccount()))
                .extracting(SchedulePix.Output::recurrenceId).containsExactly(created.id());

        runScheduledPix.execute(START.minusDays(1));
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("1000.00");

        runScheduledPix.execute(START);
        runScheduledPix.execute(START); // de novo no mesmo dia: nem débito nem ocorrência a mais
        awaitBalance(p.receiverAccount(), "200.00");
        RecurringPix.View afterFirst = recurringPix.get(created.id());
        assertThat(afterFirst.occurrences()).hasSize(2);
        assertThat(afterFirst.nextDate()).isEqualTo(START.plusMonths(1));
        assertThat(afterFirst.remaining()).isEqualTo(2);

        runScheduledPix.execute(START.plusMonths(1));
        awaitBalance(p.receiverAccount(), "400.00");
        runScheduledPix.execute(START.plusMonths(2));
        awaitBalance(p.receiverAccount(), "600.00");

        RecurringPix.View done = recurringPix.get(created.id());
        assertThat(done.status()).isEqualTo("FINISHED");
        assertThat(done.nextDate()).isNull();
        assertThat(done.remaining()).isZero();
        assertThat(done.occurrences()).extracting(RecurringPix.Occurrence::number).containsExactly(1, 2, 3);
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("400.00");
        assertThat(recurringPix.listActive(p.payerAccount())).isEmpty();

        runScheduledPix.execute(START.plusMonths(3)); // acabou: não sai mais nada
        assertThat(recurringPix.get(created.id()).occurrences()).hasSize(3);
    }

    @Test
    void semSaldoNumMes_deveRecusarSoAquele_eSeguirNoProximo() {
        Parties p = parties("0");
        RecurringPix.View created = monthly(p, "150.00", 2);

        runScheduledPix.execute(START);

        RecurringPix.View afterFailure = recurringPix.get(created.id());
        assertThat(afterFailure.occurrences().get(0).status()).isEqualTo("FAILED");
        assertThat(afterFailure.occurrences().get(0).failureReason()).contains("Saldo");
        assertThat(afterFailure.status()).isEqualTo("ACTIVE");
        assertThat(afterFailure.nextDate()).isEqualTo(START.plusMonths(1));

        depositAccount.execute(new DepositAccount.Input(p.payerAccount(), new BigDecimal("150.00")));
        runScheduledPix.execute(START.plusMonths(1));

        awaitBalance(p.receiverAccount(), "150.00");
        assertThat(recurringPix.get(created.id()).status()).isEqualTo("FINISHED");
    }

    @Test
    void cancelarUmaOcorrencia_devePularSoAquelaVez() {
        Parties p = parties("1000.00");
        RecurringPix.View created = monthly(p, "100.00", 3);
        UUID first = created.occurrences().get(0).transactionId();

        manageScheduledPix.cancel(first);

        RecurringPix.View afterSkip = recurringPix.get(created.id());
        assertThat(afterSkip.occurrences()).extracting(RecurringPix.Occurrence::status).containsExactly("CANCELLED", "PENDING");
        assertThat(afterSkip.nextDate()).isEqualTo(START.plusMonths(1));
        assertThat(afterSkip.remaining()).isEqualTo(2);

        runScheduledPix.execute(START);
        assertThat(balanceOf(p.payerAccount())).isEqualByComparingTo("1000.00");
    }

    @Test
    void cancelarARecorrencia_deveCancelarAProxima_eNaoEnviarMais() {
        Parties p = parties("1000.00");
        RecurringPix.View created = recurringPix.create(new RecurringPix.Input(p.payerAccount(), p.key(),
                Money.of("50.00"), "mesada", Frequency.WEEKLY, START, 4));

        runScheduledPix.execute(START);
        // a confirmação (PROCESSING -> COMPLETED) chega depois do crédito no destinatário
        awaitUntil(() -> recurringPix.get(created.id()).occurrences().get(0).status().equals("COMPLETED"),
                Duration.ofSeconds(15));

        RecurringPix.View cancelled = recurringPix.cancel(created.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.nextDate()).isNull();
        assertThat(cancelled.remaining()).isZero();
        assertThat(cancelled.occurrences()).extracting(RecurringPix.Occurrence::status)
                .containsExactly("COMPLETED", "CANCELLED"); // a já enviada fica como está
        assertThat(recurringPix.listActive(p.payerAccount())).isEmpty();

        runScheduledPix.execute(START.plusWeeks(1));
        runScheduledPix.execute(START.plusWeeks(2));
        sleep(Duration.ofSeconds(1));
        assertThat(balanceOf(p.receiverAccount())).isEqualByComparingTo("50.00");
        assertThat(recurringPix.get(created.id()).occurrences()).hasSize(2);
    }
}
