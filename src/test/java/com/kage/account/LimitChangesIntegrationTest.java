package com.kage.account;

import com.kage.AbstractIntegrationTest;
import com.kage.account.application.usecase.BlockAccount;
import com.kage.account.application.usecase.DepositAccount;
import com.kage.account.application.usecase.LimitChanges;
import com.kage.account.application.usecase.WithdrawAccount;
import com.kage.notification.application.usecase.ListNotifications;
import com.kage.payment.application.usecase.RegisterPixKey;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.enums.PixKeyType;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Limites ajustáveis com Postgres e RabbitMQ reais. "Passar 24h" = adiantar no banco a hora em que o
 * pedido entra em vigor (o relógio da aplicação é o de verdade).
 */
class LimitChangesIntegrationTest extends AbstractIntegrationTest {

    @Autowired LimitChanges limitChanges;
    @Autowired DepositAccount depositAccount;
    @Autowired WithdrawAccount withdrawAccount;
    @Autowired BlockAccount blockAccount;
    @Autowired RegisterPixKey registerPixKey;
    @Autowired SendPix sendPix;
    @Autowired ListNotifications listNotifications;
    @Autowired JdbcTemplate jdbcTemplate;

    private record Setup(UUID customerId, UUID accountId, String receiverKey) {}

    private Setup account(String balance) {
        UUID customerId = newActiveCustomerId();
        UUID accountId = newAccountIdFor(customerId);
        depositAccount.execute(new DepositAccount.Input(accountId, new BigDecimal(balance)));
        String key = "chave-" + UUID.randomUUID();
        registerPixKey.execute(new RegisterPixKey.Input(newAccountIdFor(newActiveCustomerId()), PixKeyType.RANDOM, key));
        return new Setup(customerId, accountId, key);
    }

    private LimitChanges.View change(UUID accountId, String pixDaily, String pixNight, String withdrawal) {
        return limitChanges.change(new LimitChanges.Input(accountId, big(pixDaily), big(pixNight), big(withdrawal), null, null));
    }

    private static BigDecimal big(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** O pedido completou as 24h. */
    private void passTheDay(UUID accountId) {
        jdbcTemplate.update("UPDATE account_limit_requests SET effective_at = ?, requested_at = ? WHERE account_id = ? "
                + "AND status = 'PENDING'", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusDays(1), accountId);
    }

    private SendPix.Output pix(Setup s, String amount) {
        return sendPix.execute(new SendPix.Input(s.accountId(), s.receiverKey(), Money.of(amount), "teste"));
    }

    @Test
    void aumento_soValeDepoisDe24h_eOClienteEAvisado() {
        Setup s = account("10000.00");

        LimitChanges.View requested = change(s.accountId(), "5000.00", "3000.00", null);

        assertThat(requested.pixDailyLimit()).isEqualByComparingTo("2000.00"); // ainda os antigos
        assertThat(requested.pixNightLimit()).isEqualByComparingTo("1000.00");
        assertThat(requested.pending()).hasSize(2);
        LimitChanges.PendingView pending = requested.pending().stream().filter(p -> p.type().equals("PIX_DAILY"))
                .findFirst().orElseThrow();
        assertThat(pending.requestedAmount()).isEqualByComparingTo("5000.00");
        assertThat(Duration.between(pending.requestedAt(), pending.effectiveAt())).isEqualTo(Duration.ofHours(24));
        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications().stream()
                .anyMatch(n -> n.type().equals("LIMIT_INCREASE_REQUESTED")), Duration.ofSeconds(15));

        // quem pegou o celular não consegue usar o limite novo agora (2100 passa do diário e do noturno antigos)
        assertThatThrownBy(() -> pix(s, "2100.00")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("limite");

        passTheDay(s.accountId());

        // o PIX aplica os aumentos vencidos na hora do envio, sem esperar o job nem a consulta
        assertThat(pix(s, "2100.00").status()).isEqualTo("PROCESSING");
        var stored = jdbcTemplate.queryForMap("SELECT pix_daily_limit, pix_night_limit FROM accounts WHERE id = ?", s.accountId());
        assertThat((BigDecimal) stored.get("pix_daily_limit")).isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) stored.get("pix_night_limit")).isEqualByComparingTo("3000.00");
        assertThat(limitChanges.view(s.accountId()).pending()).isEmpty();
    }

    @Test
    void reduzir_valeNaHora_eDerrubaOAumentoPendenteDoMesmoLimite() {
        Setup s = account("100.00");
        change(s.accountId(), null, "1500.00", null); // aumento do noturno: pendente

        LimitChanges.View view = change(s.accountId(), null, "300.00", null);

        assertThat(view.pixNightLimit()).isEqualByComparingTo("300.00");
        assertThat(view.pending()).isEmpty();
        passTheDay(s.accountId());
        assertThat(limitChanges.view(s.accountId()).pixNightLimit()).isEqualByComparingTo("300.00"); // o aumento não volta
    }

    @Test
    void cancelarOAumento_antesDeValer() {
        Setup s = account("100.00");
        UUID requestId = change(s.accountId(), null, null, "3000.00").pending().get(0).id();

        LimitChanges.View view = limitChanges.cancel(s.accountId(), requestId);

        assertThat(view.pending()).isEmpty();
        assertThatThrownBy(() -> limitChanges.cancel(s.accountId(), requestId)).isInstanceOf(NotFoundException.class);
        passTheDay(s.accountId());
        assertThat(limitChanges.view(s.accountId()).dailyWithdrawalLimit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void saque_usaOAumentoQueCompletou24h() {
        Setup s = account("5000.00");
        change(s.accountId(), null, null, "3000.00");

        assertThatThrownBy(() -> withdrawAccount.execute(new WithdrawAccount.Input(s.accountId(), new BigDecimal("2500.00"))))
                .isInstanceOf(BusinessRuleException.class);

        passTheDay(s.accountId());
        WithdrawAccount.Output output = withdrawAccount.execute(new WithdrawAccount.Input(s.accountId(), new BigDecimal("2500.00")));

        assertThat(output.balance()).isEqualByComparingTo("2500.00");
    }

    @Test
    void job_aplicaOsVencidos() {
        Setup s = account("100.00");
        change(s.accountId(), "8000.00", "4000.00", null);
        passTheDay(s.accountId());

        assertThat(limitChanges.accountsWithDue(500)).contains(s.accountId());
        limitChanges.applyDue(s.accountId());

        assertThat(limitChanges.accountsWithDue(500)).doesNotContain(s.accountId());
        var after = jdbcTemplate.queryForMap("SELECT pix_daily_limit, pix_night_limit FROM accounts WHERE id = ?", s.accountId());
        assertThat(((BigDecimal) after.get("pix_daily_limit")).doubleValue()).isCloseTo(8000.0, within(0.001));
        assertThat(((BigDecimal) after.get("pix_night_limit")).doubleValue()).isCloseTo(4000.0, within(0.001));
    }

    @Test
    void janelaNoturna_comecarAs22h_soDepoisDe24h_eVoltarParaAs20h_naHora() {
        Setup s = account("100.00");

        LimitChanges.View requested = limitChanges.change(new LimitChanges.Input(s.accountId(), null, null, null, null, null, 22));

        assertThat(requested.pixNightStartHour()).isEqualTo(20);
        assertThat(requested.pending()).extracting(LimitChanges.PendingView::type).containsExactly("PIX_NIGHT_START");
        awaitUntil(() -> listNotifications.execute(new ListNotifications.Input(s.customerId(), false)).notifications().stream()
                .anyMatch(n -> n.title().equals("Pedido para mudar a janela noturna do PIX")), Duration.ofSeconds(15));

        passTheDay(s.accountId());
        assertThat(limitChanges.view(s.accountId()).pixNightStartHour()).isEqualTo(22);
        assertThat(jdbcTemplate.queryForObject("SELECT pix_night_start_hour FROM accounts WHERE id = ?", Integer.class,
                s.accountId())).isEqualTo(22);

        LimitChanges.View back = limitChanges.change(new LimitChanges.Input(s.accountId(), null, null, null, null, null, 20));
        assertThat(back.pixNightStartHour()).isEqualTo(20); // mais proteção vale já
        assertThat(back.pending()).isEmpty();
    }

    @Test
    void validacoes() {
        Setup s = account("100.00");

        assertThatThrownBy(() -> change(s.accountId(), "25000.00", null, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("até 20000.00");
        assertThatThrownBy(() -> change(s.accountId(), null, "3000.00", null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("noturno");

        blockAccount.execute(new BlockAccount.Input(s.accountId()));
        assertThatThrownBy(() -> change(s.accountId(), "100.00", "100.00", null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("ativas");
    }
}
