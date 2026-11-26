package com.kage.account.domain.service;

import com.kage.account.domain.enums.LimitType;
import com.kage.account.domain.valueobject.Limits;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitPolicyTest {

    // padrão: PIX diário 2000, noturno 1000, saque 1000
    private static final Limits DEFAULT = Limits.defaultLimits();

    private static Map<LimitType, Money> ask(Object... pairs) {
        Map<LimitType, Money> requested = new EnumMap<>(LimitType.class);
        for (int i = 0; i < pairs.length; i += 2) requested.put((LimitType) pairs[i], Money.of((String) pairs[i + 1]));
        return requested;
    }

    @Test
    void reduzir_valeNaHora_aumentar_viraPedido() {
        LimitPolicy.Plan plan = LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_NIGHT, "200.00", LimitType.PIX_DAILY, "5000.00"));

        assertThat(plan.immediate().pixNightLimit()).isEqualTo(Money.of("200.00"));
        assertThat(plan.immediate().pixDailyLimit()).isEqualTo(Money.of("2000.00")); // o aumento ainda não vale
        assertThat(plan.increases()).containsOnlyKeys(LimitType.PIX_DAILY);
        assertThat(plan.increases().get(LimitType.PIX_DAILY)).isEqualTo(Money.of("5000.00"));
    }

    @Test
    void mesmoValor_naoViraPedido() {
        LimitPolicy.Plan plan = LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_DAILY, "2000.00"));

        assertThat(plan.increases()).isEmpty();
        assertThat(plan.immediate()).isEqualTo(DEFAULT);
    }

    @Test
    void acimaDoTeto_eRecusado() {
        assertThatThrownBy(() -> LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_DAILY, "20000.01")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("limite diário de PIX pode ser de até 20000.00");
        assertThatThrownBy(() -> LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_NIGHT, "6000.00")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void noturnoNuncaPassaDoDiurno_agoraEDepoisDosAumentos() {
        // agora: baixar o diário para 500 com o noturno em 1000
        assertThatThrownBy(() -> LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_DAILY, "500.00")))
                .hasMessage("O limite noturno de PIX não pode ser maior que o diário");
        // depois: noturno 3000 com o diário continuando em 2000
        assertThatThrownBy(() -> LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_NIGHT, "3000.00")))
                .hasMessage("O limite noturno de PIX não pode ser maior que o diário");

        // os dois juntos, cada um dentro da regra
        LimitPolicy.Plan plan = LimitPolicy.plan(DEFAULT, ask(LimitType.PIX_DAILY, "5000.00", LimitType.PIX_NIGHT, "3000.00"));
        assertThat(plan.increases()).containsOnlyKeys(LimitType.PIX_DAILY, LimitType.PIX_NIGHT);
    }

    @Test
    void aplicarAumento_noturnoParaNoDiario_seODiarioBaixouNoMeioTempo() {
        Limits lowered = DEFAULT.with(LimitType.PIX_DAILY, Money.of("1500.00"));

        Limits applied = LimitPolicy.applyIncrease(lowered, LimitType.PIX_NIGHT, Money.of("3000.00"));

        assertThat(applied.pixNightLimit()).isEqualTo(Money.of("1500.00"));
    }
}
