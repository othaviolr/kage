package com.kage.card.domain.service;

import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.shared.domain.exception.ValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantCategoryPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "5411, SUPERMARKET", "5499, SUPERMARKET", "5812, RESTAURANTS", "5814, RESTAURANTS",
            "4121, TRANSPORT", "5541, TRANSPORT", "7523, TRANSPORT",
            "4511, TRAVEL", "3000, TRAVEL", "3501, TRAVEL", "7011, TRAVEL",
            "5912, HEALTH", "8062, HEALTH", "8220, EDUCATION", "5942, EDUCATION",
            "4899, ENTERTAINMENT", "5815, ENTERTAINMENT", "7832, ENTERTAINMENT",
            "5311, SHOPPING", "5732, SHOPPING", "5999, SHOPPING", "5712, HOME", "4900, HOME",
            "4814, SERVICES", "7230, SERVICES",
            "6011, OTHER", "0001, OTHER", "9999, OTHER"
    })
    void mccDaBandeira_viraACategoria(String mcc, PurchaseCategory expected) {
        assertThat(MerchantCategoryPolicy.categoryOf(mcc)).isEqualTo(expected);
    }

    @Test
    void semMcc_eOutros_eFormatoInvalido_e400() {
        assertThat(MerchantCategoryPolicy.categoryOf(null)).isEqualTo(PurchaseCategory.OTHER);
        assertThat(MerchantCategoryPolicy.categoryOf("  ")).isEqualTo(PurchaseCategory.OTHER);
        assertThat(MerchantCategoryPolicy.normalize(" 5411 ")).isEqualTo("5411");
        for (String bad : new String[]{"541", "54111", "54A1", "-411"}) {
            assertThatThrownBy(() -> MerchantCategoryPolicy.categoryOf(bad)).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void categoriaEscolhidaPeloCliente() {
        assertThat(MerchantCategoryPolicy.parse(" health ")).isEqualTo(PurchaseCategory.HEALTH);
        assertThatThrownBy(() -> MerchantCategoryPolicy.parse("SAQUE")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("OTHER");
        assertThatThrownBy(() -> MerchantCategoryPolicy.parse(null)).isInstanceOf(ValidationException.class);
    }
}
