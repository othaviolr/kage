package com.kage.card.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacCardNumberServiceTest {

    private static final String SECRET = "test-secret-with-at-least-32-bytes-0123456789";
    private final HmacCardNumberService service = new HmacCardNumberService(SECRET, "529999");

    @Test
    void luhn_exemploClassico() {
        assertThat(HmacCardNumberService.luhnDigit("7992739871")).isEqualTo(3);
        assertThat(HmacCardNumberService.isLuhnValid("79927398713")).isTrue();
        assertThat(HmacCardNumberService.isLuhnValid("79927398710")).isFalse();
    }

    @Test
    void numero_tem16DigitosComBinELuhn_eEhSempreOMesmoParaOMesmoCartao() {
        UUID id = UUID.randomUUID();
        String number = service.numberFor(id);

        assertThat(number).hasSize(16).startsWith("529999").matches("\\d{16}");
        assertThat(HmacCardNumberService.isLuhnValid(number)).isTrue();
        assertThat(service.numberFor(id)).isEqualTo(number);
    }

    @Test
    void cartoesDiferentes_numerosDiferentes() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 500; i++) numbers.add(service.numberFor(UUID.randomUUID()));
        assertThat(numbers).hasSize(500);
    }

    @Test
    void outroSegredo_outroNumero() {
        UUID id = UUID.randomUUID();
        HmacCardNumberService other = new HmacCardNumberService(SECRET + "x", "529999");
        assertThat(other.numberFor(id)).isNotEqualTo(service.numberFor(id));
    }

    @Test
    void cvv_tem3Digitos_eMudaComAValidade() {
        String number = service.numberFor(UUID.randomUUID());
        String cvv = service.cvvFor(number, YearMonth.of(2031, 11));

        assertThat(cvv).matches("\\d{3}");
        assertThat(service.cvvFor(number, YearMonth.of(2031, 11))).isEqualTo(cvv);
        // 1000 combinações: validades diferentes quase sempre dão CVV diferente; testa várias
        Set<String> cvvs = new HashSet<>();
        for (int month = 1; month <= 12; month++) cvvs.add(service.cvvFor(number, YearMonth.of(2031, month)));
        assertThat(cvvs.size()).isGreaterThan(1);
    }

    @Test
    void hash_naoRevelaONumero() {
        String number = service.numberFor(UUID.randomUUID());
        String hash = service.hash(number);

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(number.substring(6));
        assertThat(service.hash(number)).isEqualTo(hash);
    }

    @Test
    void configuracaoInvalida() {
        assertThatThrownBy(() -> new HmacCardNumberService("curto", "529999")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacCardNumberService(SECRET, "52999")).isInstanceOf(IllegalArgumentException.class);
    }
}
