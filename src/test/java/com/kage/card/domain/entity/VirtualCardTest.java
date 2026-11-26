package com.kage.card.domain.entity;

import com.kage.card.domain.enums.VirtualCardKind;
import com.kage.card.domain.enums.VirtualCardStatus;
import com.kage.shared.domain.DomainTime;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualCardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 10, 0);

    @BeforeEach
    void fixClock() {
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(NOW.atZone(zone).toInstant(), zone));
    }

    @AfterEach
    void resetClock() {
        DomainTime.reset();
    }

    private static VirtualCard create(VirtualCardKind kind) {
        return VirtualCard.create(UUID.randomUUID(), UUID.randomUUID(), "  Streaming  ", kind, "1234", "hash");
    }

    @Test
    void permanente_vale5Anos_semHoraParaExpirar() {
        VirtualCard v = create(VirtualCardKind.PERMANENT);

        assertThat(v.nickname()).isEqualTo("Streaming");
        assertThat(v.status()).isEqualTo(VirtualCardStatus.ACTIVE);
        assertThat(v.expiry()).isEqualTo(YearMonth.of(2031, 11));
        assertThat(v.expiresAt()).isNull();
        assertThat(v.isExpired(NOW.plusYears(5))).isFalse();
        assertThat(v.isExpired(NOW.plusYears(5).plusMonths(1))).isTrue();
    }

    @Test
    void temporario_expiraEm24h() {
        VirtualCard v = create(VirtualCardKind.TEMPORARY);

        assertThat(v.expiresAt()).isEqualTo(NOW.plusHours(24));
        assertThat(v.isExpired(NOW.plusHours(23))).isFalse();
        assertThat(v.isExpired(NOW.plusHours(24))).isTrue();
        assertThat(v.displayStatus(NOW.plusHours(25))).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> v.requireUsable(NOW.plusHours(25)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("expirado");
    }

    @Test
    void nomeETipo_saoObrigatorios() {
        assertThatThrownBy(() -> VirtualCard.create(UUID.randomUUID(), UUID.randomUUID(), " ", VirtualCardKind.PERMANENT, "1", "h"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> VirtualCard.create(UUID.randomUUID(), UUID.randomUUID(), "x".repeat(31), VirtualCardKind.PERMANENT, "1", "h"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> VirtualCard.create(UUID.randomUUID(), UUID.randomUUID(), "Loja", null, "1", "h"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void bloquear_desbloquear_apagar() {
        VirtualCard blocked = create(VirtualCardKind.PERMANENT).block();
        assertThatThrownBy(blocked::block).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> blocked.requireUsable(NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("bloqueado");

        VirtualCard active = blocked.unblock();
        assertThat(active.status()).isEqualTo(VirtualCardStatus.ACTIVE);
        assertThatThrownBy(active::unblock).isInstanceOf(BusinessRuleException.class);
        active.requireUsable(NOW);

        VirtualCard deleted = active.delete();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.deletedAt()).isEqualTo(NOW);
        assertThat(deleted.displayStatus(NOW)).isEqualTo("DELETED");
        assertThatThrownBy(deleted::delete).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(deleted::unblock).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> deleted.requireUsable(NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("apagado");
    }

    @Test
    void temporarioExpirado_naoDesbloqueia() {
        VirtualCard blocked = create(VirtualCardKind.TEMPORARY).block();
        ZoneId zone = ZoneId.systemDefault();
        DomainTime.use(Clock.fixed(NOW.plusDays(2).atZone(zone).toInstant(), zone));

        assertThatThrownBy(blocked::unblock).isInstanceOf(BusinessRuleException.class).hasMessageContaining("expirado");
    }
}
