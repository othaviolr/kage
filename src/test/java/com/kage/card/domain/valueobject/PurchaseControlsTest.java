package com.kage.card.domain.valueobject;

import com.kage.card.domain.enums.PurchaseChannel;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseControlsTest {

    @Test
    void tudoLigado_tudoPassa() {
        for (PurchaseChannel channel : PurchaseChannel.values()) {
            assertThatCode(() -> PurchaseControls.ALL_ALLOWED.check(channel, true, false)).doesNotThrowAnyException();
        }
    }

    @Test
    void onlineDesligado_valeSoParaOFisico() {
        PurchaseControls controls = PurchaseControls.ALL_ALLOWED.with(false, null, null);
        assertThatThrownBy(() -> controls.check(PurchaseChannel.ONLINE, false, false))
                .isInstanceOf(BusinessRuleException.class).hasMessageStartingWith("Compras online com o cartão físico");
        assertThatCode(() -> controls.check(PurchaseChannel.ONLINE, false, true)).doesNotThrowAnyException(); // virtual
        assertThatCode(() -> controls.check(PurchaseChannel.CHIP, false, false)).doesNotThrowAnyException();
        assertThatCode(() -> controls.check(PurchaseChannel.CONTACTLESS, false, false)).doesNotThrowAnyException();
    }

    @Test
    void internacionalDesligado_valeParaQualquerCartaoECanal() {
        PurchaseControls controls = PurchaseControls.ALL_ALLOWED.with(null, false, null);
        assertThatThrownBy(() -> controls.check(PurchaseChannel.ONLINE, true, true))
                .hasMessageStartingWith("Compras internacionais estão desligadas");
        assertThatThrownBy(() -> controls.check(PurchaseChannel.CHIP, true, false)).isInstanceOf(BusinessRuleException.class);
        assertThatCode(() -> controls.check(PurchaseChannel.CHIP, false, false)).doesNotThrowAnyException();
    }

    @Test
    void aproximacaoDesligada_inserirOCartaoPassa() {
        PurchaseControls controls = PurchaseControls.ALL_ALLOWED.with(null, null, false);
        assertThatThrownBy(() -> controls.check(PurchaseChannel.CONTACTLESS, false, false))
                .hasMessageStartingWith("Compras por aproximação estão desligadas");
        assertThatCode(() -> controls.check(PurchaseChannel.CHIP, false, false)).doesNotThrowAnyException();
    }

    @Test
    void with_nuloNaoMuda() {
        PurchaseControls changed = new PurchaseControls(false, true, false).with(null, false, null);
        assertThat(changed).isEqualTo(new PurchaseControls(false, false, false));
    }
}
