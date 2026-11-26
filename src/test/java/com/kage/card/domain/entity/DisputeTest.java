package com.kage.card.domain.entity;

import com.kage.card.domain.enums.DisputeReason;
import com.kage.card.domain.enums.DisputeStatus;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeTest {

    private static Dispute open(DisputeReason reason, String details) {
        return Dispute.open(UUID.randomUUID(), UUID.randomUUID(), "Loja", Money.of("300.00"), Money.of("100.00"),
                "2026-10", reason, details, false);
    }

    @Test
    void abre_emAnalise() {
        Dispute d = open(DisputeReason.NOT_RECEIVED, "  Comprei e não chegou  ");

        assertThat(d.status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.details()).isEqualTo("Comprei e não chegou");
        assertThat(d.openedAt()).isNotNull();
        assertThat(d.resolvedAt()).isNull();
    }

    @Test
    void fraude_dispensaDetalhes_osOutrosMotivosNao() {
        assertThat(open(DisputeReason.NOT_RECOGNIZED, null).details()).isNull();
        assertThatThrownBy(() -> open(DisputeReason.DUPLICATE_CHARGE, " "))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> open(null, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> open(DisputeReason.NOT_RECEIVED, "x".repeat(501)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aceita() {
        Dispute accepted = open(DisputeReason.NOT_RECEIVED, "não chegou").accept(null);

        assertThat(accepted.status()).isEqualTo(DisputeStatus.ACCEPTED);
        assertThat(accepted.resolvedAt()).isNotNull();
        assertThat(accepted.reversalMonth()).isNull();
    }

    @Test
    void negar_exigeMotivo() {
        Dispute d = open(DisputeReason.NOT_RECEIVED, "não chegou");

        assertThatThrownBy(() -> d.reject("  ", "2026-11")).isInstanceOf(ValidationException.class);

        Dispute rejected = d.reject("Entrega comprovada pelo estabelecimento", "2026-11");
        assertThat(rejected.status()).isEqualTo(DisputeStatus.REJECTED);
        assertThat(rejected.resolutionNote()).isEqualTo("Entrega comprovada pelo estabelecimento");
        assertThat(rejected.reversalMonth()).isEqualTo("2026-11");
    }

    @Test
    void resolvida_naoResolveDeNovo() {
        Dispute accepted = open(DisputeReason.NOT_RECEIVED, "não chegou").accept("ok");

        assertThatThrownBy(() -> accepted.accept("de novo")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> accepted.reject("não", "2026-11")).isInstanceOf(BusinessRuleException.class);
    }
}
