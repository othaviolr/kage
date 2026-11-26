package com.kage.payment.domain.entity;

import com.kage.payment.domain.entity.PixAutomaticAuthorization.DisplayStatus;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Frequency;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.Party;
import com.kage.payment.domain.entity.PixAutomaticAuthorization.RetryPolicy;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixAutomaticAuthorizationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);
    private static final LocalDateTime NOW = TODAY.atTime(10, 0);

    private PixAutomaticAuthorization request(Frequency frequency, Money fixed, LocalDate start, LocalDate end) {
        return request(frequency, fixed, RetryPolicy.NONE, start, end);
    }

    private PixAutomaticAuthorization request(Frequency frequency, Money fixed, RetryPolicy retry, LocalDate start,
                                              LocalDate end) {
        return PixAutomaticAuthorization.request(UUID.randomUUID(), UUID.randomUUID(), "chave-academia", "Academia Forte",
                "Mensalidade", "contrato 42", frequency, fixed, retry, start, end, TODAY, NOW);
    }

    private PixAutomaticAuthorization active(Frequency frequency, Money fixed, Money max, LocalDate start) {
        PixAutomaticAuthorization a = request(frequency, fixed, start, null);
        a.approve(max, TODAY, NOW);
        return a;
    }

    @Test
    void pedido_deveValidarPartesPeriodoEDescricao() {
        UUID same = UUID.randomUUID();
        assertThatThrownBy(() -> PixAutomaticAuthorization.request(same, same, "k", "X", "Mensalidade", null,
                Frequency.MONTHLY, null, RetryPolicy.NONE, TODAY.plusDays(5), null, TODAY, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("mesma conta");
        assertThatThrownBy(() -> request(Frequency.MONTHLY, null, TODAY, null))
                .isInstanceOf(DomainException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> request(Frequency.MONTHLY, null, TODAY.plusDays(10), TODAY.plusDays(5)))
                .isInstanceOf(DomainException.class).hasMessageContaining("fim");
        assertThatThrownBy(() -> PixAutomaticAuthorization.request(UUID.randomUUID(), UUID.randomUUID(), "k", "X", " ",
                null, Frequency.MONTHLY, null, RetryPolicy.NONE, TODAY.plusDays(5), null, TODAY, NOW))
                .isInstanceOf(ValidationException.class);

        assertThat(request(Frequency.MONTHLY, Money.of("49.90"), TODAY.plusDays(5), null).getStatus())
                .isEqualTo(PixAutomaticAuthorization.Status.PENDING);
    }

    @Test
    void autorizar_tetoSoNoValorVariavel_eAteAData_deInicio() {
        PixAutomaticAuthorization fixed = request(Frequency.MONTHLY, Money.of("49.90"), TODAY.plusDays(5), null);
        assertThatThrownBy(() -> fixed.approve(Money.of("100.00"), TODAY, NOW)).isInstanceOf(ValidationException.class);

        PixAutomaticAuthorization variable = request(Frequency.MONTHLY, null, TODAY.plusDays(5), null);
        variable.approve(Money.of("100.00"), TODAY, NOW);
        assertThat(variable.getMaxAmount()).isEqualTo(Money.of("100.00"));
        assertThatThrownBy(() -> variable.reject(TODAY, NOW)).hasMessageContaining("já foi respondido");

        PixAutomaticAuthorization late = request(Frequency.MONTHLY, null, TODAY.plusDays(5), null);
        assertThat(late.displayStatus(TODAY.plusDays(6))).isEqualTo(DisplayStatus.EXPIRED);
        assertThatThrownBy(() -> late.approve(null, TODAY.plusDays(6), NOW)).hasMessageContaining("expirou");
    }

    @Test
    void ciclos_contadosDoInicio_mensalNoDia31() {
        PixAutomaticAuthorization a = active(Frequency.MONTHLY, null, null, LocalDate.of(2027, 1, 31));

        assertThat(a.cycleOf(LocalDate.of(2027, 1, 31))).isZero();
        assertThat(a.cycleOf(LocalDate.of(2027, 2, 27))).isZero();
        assertThat(a.cycleOf(LocalDate.of(2027, 2, 28))).isEqualTo(1); // 31/01 + 1 mês
        assertThat(a.cycleOf(LocalDate.of(2027, 3, 30))).isEqualTo(1);
        assertThat(a.cycleOf(LocalDate.of(2027, 3, 31))).isEqualTo(2);

        PixAutomaticAuthorization weekly = active(Frequency.WEEKLY, null, null, LocalDate.of(2026, 12, 1));
        assertThat(weekly.cycleOf(LocalDate.of(2026, 12, 14))).isEqualTo(1);
        assertThat(weekly.cycleOf(LocalDate.of(2026, 12, 15))).isEqualTo(2);

        PixAutomaticAuthorization quarterly = active(Frequency.QUARTERLY, null, null, LocalDate.of(2026, 12, 1));
        assertThat(quarterly.cycleOf(LocalDate.of(2027, 2, 28))).isZero();
        assertThat(quarterly.cycleOf(LocalDate.of(2027, 3, 1))).isEqualTo(1);
    }

    @Test
    void cobranca_deveRespeitarJanelaPeriodoEValor() {
        LocalDate start = TODAY.plusDays(1);
        PixAutomaticAuthorization fixed = active(Frequency.MONTHLY, Money.of("49.90"), null, start);

        assertThat(fixed.cycleForCharge(Money.of("49.90"), TODAY.plusDays(2), TODAY)).isZero();
        assertThat(fixed.cycleForCharge(Money.of("49.90"), TODAY.plusDays(10), TODAY)).isZero();
        assertThatThrownBy(() -> fixed.cycleForCharge(Money.of("49.90"), TODAY.plusDays(1), TODAY))
                .hasMessageContaining("de 2 a 10 dias");
        assertThatThrownBy(() -> fixed.cycleForCharge(Money.of("49.90"), TODAY.plusDays(11), TODAY))
                .hasMessageContaining("de 2 a 10 dias");
        assertThatThrownBy(() -> fixed.cycleForCharge(Money.of("50.00"), TODAY.plusDays(5), TODAY))
                .hasMessageContaining("valor fixo");

        PixAutomaticAuthorization capped = active(Frequency.MONTHLY, null, Money.of("100.00"), start);
        assertThat(capped.cycleForCharge(Money.of("100.00"), TODAY.plusDays(5), TODAY)).isZero();
        assertThatThrownBy(() -> capped.cycleForCharge(Money.of("100.01"), TODAY.plusDays(5), TODAY))
                .hasMessageContaining("máximo");

        PixAutomaticAuthorization later = active(Frequency.MONTHLY, null, null, TODAY.plusDays(20));
        assertThatThrownBy(() -> later.cycleForCharge(Money.of("10.00"), TODAY.plusDays(5), TODAY))
                .hasMessageContaining("fora do período");
    }

    @Test
    void cancelada_ouPendente_naoRecebeCobranca_eFimEncerra() {
        PixAutomaticAuthorization pending = request(Frequency.MONTHLY, null, TODAY.plusDays(1), null);
        assertThatThrownBy(() -> pending.cycleForCharge(Money.of("10.00"), TODAY.plusDays(5), TODAY))
                .hasMessageContaining("não está ativa");

        PixAutomaticAuthorization a = active(Frequency.MONTHLY, null, null, TODAY.plusDays(1));
        a.cancel(Party.PAYER, NOW);
        assertThat(a.getCancelledBy()).isEqualTo(Party.PAYER);
        assertThatThrownBy(() -> a.cycleForCharge(Money.of("10.00"), TODAY.plusDays(5), TODAY))
                .hasMessageContaining("não está ativa");
        assertThatThrownBy(() -> a.cancel(Party.RECEIVER, NOW)).hasMessageContaining("encerrada");

        PixAutomaticAuthorization bounded = request(Frequency.MONTHLY, null, TODAY.plusDays(1), TODAY.plusMonths(3));
        bounded.approve(null, TODAY, NOW);
        assertThat(bounded.displayStatus(TODAY.plusMonths(3))).isEqualTo(DisplayStatus.ACTIVE);
        assertThat(bounded.displayStatus(TODAY.plusMonths(3).plusDays(1))).isEqualTo(DisplayStatus.FINISHED);
    }

    @Test
    void novaTentativa_soSePrevista_ate3_umaPorDia_ate7DiasDepoisDoVencimento() {
        LocalDate due = TODAY.plusDays(5);
        PixAutomaticAuthorization noRetry = active(Frequency.MONTHLY, null, null, TODAY.plusDays(1));
        assertThatThrownBy(() -> noRetry.checkRetry(due, due, 1, due.plusDays(1), due))
                .hasMessageContaining("não permite novas tentativas");

        PixAutomaticAuthorization a = request(Frequency.MONTHLY, null, RetryPolicy.THREE_IN_SEVEN_DAYS, TODAY.plusDays(1), null);
        a.approve(null, TODAY, NOW);

        a.checkRetry(due, due, 1, due.plusDays(1), due);              // dia seguinte ao vencimento
        a.checkRetry(due, due.plusDays(3), 3, due.plusDays(7), due);  // 3ª nova tentativa, no 7º dia
        assertThatThrownBy(() -> a.checkRetry(due, due.plusDays(5), 4, due.plusDays(6), due))
                .hasMessageContaining("3 novas tentativas");
        assertThatThrownBy(() -> a.checkRetry(due, due, 1, due.plusDays(8), due))
                .hasMessageContaining("7 dias depois do vencimento");
        assertThatThrownBy(() -> a.checkRetry(due, due.plusDays(2), 2, due.plusDays(2), due.plusDays(1)))
                .hasMessageContaining("Uma tentativa por dia");
        assertThatThrownBy(() -> a.checkRetry(due, due, 1, due, due))
                .hasMessageContaining("a partir de amanhã");

        a.cancel(Party.PAYER, NOW);
        assertThatThrownBy(() -> a.checkRetry(due, due, 1, due.plusDays(1), due)).hasMessageContaining("não está ativa");
    }
}
