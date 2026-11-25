package com.kage.card.domain.entity;

import com.kage.card.domain.enums.InvoiceItemKind;
import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pagamento parcial e virada na fatura; teto legal dos encargos no cartão. */
class RevolvingCreditDomainTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2033, 1, 15, 10, 0);

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoice = Invoice.open(UUID.randomUUID(), YearMonth.of(2033, 1), 10, 20);
        invoice.addItem(UUID.randomUUID(), "Notebook", Money.of("1000.00"), LocalDateTime.of(2033, 1, 5, 10, 0));
        invoice.close();
    }

    // ---------- fatura ----------

    @Test
    void pagamentosParciais_acumulam_eQuitarViraPaid() {
        assertThat(invoice.minimumPayment()).isEqualTo(Money.of("150.00"));

        invoice.registerPayment(Money.of("300.00"), NOW);
        invoice.registerPayment(Money.of("200.00"), NOW);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(invoice.getPaidAmount()).isEqualTo(Money.of("500.00"));
        assertThat(invoice.remaining()).isEqualTo(Money.of("500.00"));

        invoice.registerPayment(Money.of("500.00"), NOW);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void naoAceitaPagarMaisQueOSaldo_nemZero() {
        invoice.registerPayment(Money.of("900.00"), NOW);

        assertThatThrownBy(() -> invoice.registerPayment(Money.of("100.01"), NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("saldo devedor");
        assertThatThrownBy(() -> invoice.registerPayment(Money.ZERO, NOW)).isInstanceOf(ValidationException.class);
    }

    @Test
    void virada_levaOSaldo_eAFaturaNaoAceitaMaisPagamento() {
        invoice.registerPayment(Money.of("400.00"), NOW);

        Money carried = invoice.rollOver(NOW);

        assertThat(carried).isEqualTo(Money.of("600.00"));
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ROLLED_OVER);
        assertThatThrownBy(() -> invoice.registerPayment(Money.of("10.00"), NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("pague a fatura atual");
    }

    @Test
    void encargos_entramNaFaturaSeguinte_umaVezSo() {
        Invoice next = Invoice.open(invoice.getCardId(), YearMonth.of(2033, 2), 10, 20);
        UUID source = invoice.getId();

        next.addCharge(InvoiceItemKind.PREVIOUS_BALANCE, source, "Saldo anterior", Money.of("600.00"), NOW);
        next.addCharge(InvoiceItemKind.REVOLVING_INTEREST, source, "Juros", Money.of("84.00"), NOW);
        next.addCharge(InvoiceItemKind.REVOLVING_INTEREST, source, "Juros", Money.of("84.00"), NOW); // repetido
        next.addCharge(InvoiceItemKind.LATE_FINE, source, "Multa", Money.ZERO, NOW);                // zero não vira linha

        assertThat(next.getItems()).hasSize(2);
        assertThat(next.total()).isEqualTo(Money.of("684.00"));
        assertThat(next.hasPreviousBalance()).isTrue();
    }

    // ---------- teto legal no cartão ----------

    @Test
    void teto_encargosNuncaPassamDaDividaOriginal() {
        Card card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("5000.00"), 10, 20);
        card.authorizePurchase(Money.of("100.00"));

        // virada 1: dívida de 100 vira o principal; 30 de encargos cabem
        assertThat(card.applyRevolvingCharges(Money.of("100.00"), Money.of("30.00"))).isEqualTo(Money.of("30.00"));
        // virada 2: propõe 50, mas só restam 70 de espaço -> cabe tudo
        assertThat(card.applyRevolvingCharges(Money.of("130.00"), Money.of("50.00"))).isEqualTo(Money.of("50.00"));
        // virada 3: propõe 40, restam 20
        assertThat(card.applyRevolvingCharges(Money.of("180.00"), Money.of("40.00"))).isEqualTo(Money.of("20.00"));
        // virada 4: teto atingido
        assertThat(card.applyRevolvingCharges(Money.of("200.00"), Money.of("40.00"))).isEqualTo(Money.ZERO);

        assertThat(card.getRevolvingCharges()).isEqualTo(card.getRevolvingPrincipal());
        assertThat(card.getUsedLimit()).isEqualTo(Money.of("200.00")); // 100 de compra + 100 de encargos
    }

    @Test
    void quitar_reiniciaOTeto() {
        Card card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("5000.00"), 10, 20);
        card.authorizePurchase(Money.of("100.00"));
        card.applyRevolvingCharges(Money.of("100.00"), Money.of("100.00"));

        card.settleRevolving();

        assertThat(card.hasRevolvingDebt()).isFalse();
        assertThat(card.applyRevolvingCharges(Money.of("50.00"), Money.of("10.00"))).isEqualTo(Money.of("10.00"));
    }

    @Test
    void limiteDisponivel_ficaZero_quandoEncargosPassamDoLimite() {
        Card card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
        card.authorizePurchase(Money.of("1000.00"));
        card.applyRevolvingCharges(Money.of("1000.00"), Money.of("167.00"));

        assertThat(card.availableLimit()).isEqualTo(Money.ZERO);
        assertThatThrownBy(() -> card.authorizePurchase(Money.of("1.00"))).isInstanceOf(BusinessRuleException.class);
    }
}
