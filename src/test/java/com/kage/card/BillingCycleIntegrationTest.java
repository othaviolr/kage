package com.kage.card;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.domain.repository.AccountRepository;
import com.kage.card.application.usecase.GetCard;
import com.kage.card.application.usecase.GetInvoice;
import com.kage.card.application.usecase.IssueCard;
import com.kage.card.application.usecase.RegisterPurchase;
import com.kage.card.application.usecase.RunBillingCycle;
import com.kage.card.application.usecase.SetAutoDebit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ciclo de cobrança com Postgres real. Cartões fecham dia 10 e vencem dia 20; as compras são de
 * março/2031 para não colidir com as datas dos outros testes (o banco é compartilhado, e o ciclo
 * varre todas as faturas — por isso as asserções olham só as faturas deste teste).
 */
class BillingCycleIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime PURCHASE = LocalDateTime.of(2031, 3, 5, 10, 0);
    private static final String MONTH = "2031-03";
    private static final LocalDate CLOSING_DAY = LocalDate.of(2031, 3, 10);
    private static final LocalDate DUE_DAY = LocalDate.of(2031, 3, 20);

    @Autowired IssueCard issueCard;
    @Autowired RegisterPurchase registerPurchase;
    @Autowired SetAutoDebit setAutoDebit;
    @Autowired RunBillingCycle runBillingCycle;
    @Autowired GetInvoice getInvoice;
    @Autowired GetCard getCard;
    @Autowired DepositAccount depositAccount;
    @Autowired AccountRepository accountRepository;

    private record Setup(UUID cardId, UUID accountId) {}

    private Setup cardWith300(boolean autoDebit, String balance) {
        IssueCard.Input input = issueCardInput(new BigDecimal("1000.00"), 10, 20);
        UUID cardId = issueCard.execute(input).cardId();
        registerPurchase.execute(new RegisterPurchase.Input(cardId, UUID.randomUUID(), "Assinatura", new BigDecimal("300.00"), PURCHASE));
        if (autoDebit) setAutoDebit.execute(new SetAutoDebit.Input(cardId, true));
        if (new BigDecimal(balance).signum() > 0) depositAccount.execute(new DepositAccount.Input(input.accountId(), new BigDecimal(balance)));
        return new Setup(cardId, input.accountId());
    }

    private String statusOf(Setup s) {
        return getInvoice.execute(new GetInvoice.Input(s.cardId(), MONTH)).status();
    }

    private BigDecimal balanceOf(Setup s) {
        return accountRepository.findById(s.accountId()).orElseThrow().getBalance().amount();
    }

    @Test
    void deveFecharSoDepoisDoCorte_eCobrarNoVencimento() {
        Setup s = cardWith300(true, "1000.00");

        runBillingCycle.execute(CLOSING_DAY);              // compras do dia 10 ainda entram nesta fatura
        assertThat(statusOf(s)).isEqualTo("OPEN");

        runBillingCycle.execute(CLOSING_DAY.plusDays(1));
        assertThat(statusOf(s)).isEqualTo("CLOSED");
        assertThat(balanceOf(s)).isEqualByComparingTo("1000.00"); // fechada, mas ainda não venceu

        runBillingCycle.execute(DUE_DAY);
        assertThat(statusOf(s)).isEqualTo("PAID");
        assertThat(balanceOf(s)).isEqualByComparingTo("700.00");
        assertThat(getCard.execute(new GetCard.Input(s.cardId())).availableLimit()).isEqualByComparingTo("1000.00");

        // rodar de novo no mesmo dia não cobra outra vez
        runBillingCycle.execute(DUE_DAY);
        assertThat(balanceOf(s)).isEqualByComparingTo("700.00");
    }

    @Test
    void semSaldoNoVencimento_deveFicarEmAtraso_eCobrarQuandoEntrarDinheiro() {
        Setup s = cardWith300(true, "50.00");
        runBillingCycle.execute(CLOSING_DAY.plusDays(1));

        RunBillingCycle.Output dueDay = runBillingCycle.execute(DUE_DAY);

        assertThat(dueDay.failures()).anySatisfy(f -> {
            assertThat(f.cardId()).isEqualTo(s.cardId());
            assertThat(f.step()).isEqualTo("AUTO_DEBIT");
        });
        assertThat(statusOf(s)).isEqualTo("CLOSED");
        assertThat(balanceOf(s)).isEqualByComparingTo("50.00");

        depositAccount.execute(new DepositAccount.Input(s.accountId(), new BigDecimal("500.00")));
        runBillingCycle.execute(DUE_DAY.plusDays(1));

        assertThat(statusOf(s)).isEqualTo("PAID");
        assertThat(balanceOf(s)).isEqualByComparingTo("250.00");
    }

    @Test
    void semDebitoAutomatico_deveFecharMasNaoCobrar() {
        Setup s = cardWith300(false, "1000.00");

        runBillingCycle.execute(DUE_DAY);

        assertThat(statusOf(s)).isEqualTo("CLOSED");
        assertThat(balanceOf(s)).isEqualByComparingTo("1000.00");
    }
}
