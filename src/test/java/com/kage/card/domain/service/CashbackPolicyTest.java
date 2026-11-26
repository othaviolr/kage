package com.kage.card.domain.service;

import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CashbackPolicyTest {

    private static final LocalDateTime AT = LocalDateTime.of(2034, 1, 5, 10, 0);
    private final UUID disputed = UUID.randomUUID();

    private Invoice invoice() {
        Invoice invoice = Invoice.open(UUID.randomUUID(), YearMonth.of(2034, 1), 10, 20);
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of("600.00"), AT);
        invoice.addItem(disputed, "Loja", Money.of("399.99"), AT);
        invoice.addCharge(InvoiceItemKind.IOF, UUID.randomUUID(), "IOF", Money.of("3.81"), AT); // não é compra
        invoice.close();
        return invoice;
    }

    @Test
    void base_soCompras_semAsContestadas() {
        Invoice invoice = invoice();

        assertThat(CashbackPolicy.base(invoice, Set.of()).amount()).isEqualByComparingTo("999.99");
        assertThat(CashbackPolicy.base(invoice, Set.of(disputed)).amount()).isEqualByComparingTo("600.00");
    }

    @Test
    void umPorCento_arredondadoParaBaixo() {
        assertThat(CashbackPolicy.of(Money.of("999.99")).amount()).isEqualByComparingTo("9.99"); // 9,9999
        assertThat(CashbackPolicy.of(Money.of("0.99")).amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void soFaturaPagaInteiraAteOVencimento() {
        Invoice onTime = invoice();
        onTime.registerPayment(onTime.total(), LocalDateTime.of(2034, 1, 20, 23, 0)); // no dia do vencimento
        assertThat(CashbackPolicy.eligible(onTime)).isTrue();

        Invoice late = invoice();
        late.registerPayment(late.total(), LocalDateTime.of(2034, 1, 21, 8, 0));
        assertThat(CashbackPolicy.eligible(late)).isFalse();

        Invoice partial = invoice();
        partial.registerPayment(Money.of("100.00"), LocalDateTime.of(2034, 1, 15, 8, 0));
        assertThat(CashbackPolicy.eligible(partial)).isFalse(); // ainda não quitada
    }
}
