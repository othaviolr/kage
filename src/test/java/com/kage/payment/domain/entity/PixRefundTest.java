package com.kage.payment.domain.entity;

import com.kage.payment.domain.enums.RefundStatus;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PixRefundTest {

    private PixRefund newRefund() {
        return PixRefund.create(UUID.randomUUID(), "cobrança indevida", Money.of("30.00"));
    }

    @Test
    void fail_deveRegistrarMotivo_quandoAprovado() {
        PixRefund refund = newRefund();
        refund.approve("admin");

        refund.fail("Saldo disponível insuficiente");

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getFailureReason()).isEqualTo("Saldo disponível insuficiente");
    }

    @Test
    void fail_deveLancar_quandoAindaNaoAprovado() {
        assertThatThrownBy(() -> newRefund().fail("x")).isInstanceOf(DomainException.class);
    }

    @Test
    void complete_naoDevePermitir_depoisDeFalhar() {
        PixRefund refund = newRefund();
        refund.approve("admin");
        refund.fail("x");

        assertThatThrownBy(refund::complete).isInstanceOf(DomainException.class);
    }

    @Test
    void create_deveRecusarValorZero() {
        assertThatThrownBy(() -> PixRefund.create(UUID.randomUUID(), "motivo", Money.ZERO))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("maior que zero");
    }
}
