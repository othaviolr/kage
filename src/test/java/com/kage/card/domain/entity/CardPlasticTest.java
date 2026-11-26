package com.kage.card.domain.entity;

import com.kage.card.domain.enums.PlasticReason;
import com.kage.card.domain.enums.PlasticStatus;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardPlasticTest {

    private final UUID cardId = UUID.randomUUID();

    @Test
    void primeiro_nasceAtivo_comValidadeDe5Anos() {
        CardPlastic p = CardPlastic.first(UUID.randomUUID(), cardId, "1234", "hash");

        assertThat(p.status()).isEqualTo(PlasticStatus.ACTIVE);
        assertThat(p.sequence()).isEqualTo(1);
        assertThat(p.issueReason()).isEqualTo(PlasticReason.FIRST_ISSUE);
        assertThat(p.activatedAt()).isNotNull();
        assertThat(p.expiry()).isEqualTo(YearMonth.now().plusYears(5));
    }

    @Test
    void segundaVia_chegaAguardandoDesbloqueio() {
        CardPlastic p = CardPlastic.replacement(UUID.randomUUID(), cardId, 2, "5678", "hash", PlasticReason.LOST);

        assertThat(p.status()).isEqualTo(PlasticStatus.PENDING_ACTIVATION);
        assertThat(p.activatedAt()).isNull();
        assertThatThrownBy(() -> CardPlastic.replacement(UUID.randomUUID(), cardId, 2, "5678", "h", PlasticReason.REPLACED))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void desbloqueio_confereOsQuatroUltimosDigitos() {
        CardPlastic p = CardPlastic.replacement(UUID.randomUUID(), cardId, 2, "5678", "hash", PlasticReason.DAMAGED);

        assertThatThrownBy(() -> p.activate("12")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> p.activate("1234")).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não conferem");

        CardPlastic active = p.activate(" 5678 ");
        assertThat(active.isActive()).isTrue();
        assertThat(active.activatedAt()).isNotNull();
        assertThatThrownBy(() -> active.activate("5678")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void cancelar_ehDefinitivo() {
        CardPlastic cancelled = CardPlastic.first(UUID.randomUUID(), cardId, "1234", "h").cancel(PlasticReason.STOLEN);

        assertThat(cancelled.status()).isEqualTo(PlasticStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(PlasticReason.STOLEN);
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThatThrownBy(() -> cancelled.cancel(PlasticReason.LOST)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void motivos() {
        assertThat(PlasticReason.LOST.compromisesNumber()).isTrue();
        assertThat(PlasticReason.STOLEN.compromisesNumber()).isTrue();
        assertThat(PlasticReason.FRAUD.compromisesNumber()).isTrue();
        assertThat(PlasticReason.DAMAGED.compromisesNumber()).isFalse();
        assertThat(PlasticReason.DAMAGED.isReissueReason()).isTrue();
        assertThat(PlasticReason.FIRST_ISSUE.isReissueReason()).isFalse();
    }
}
