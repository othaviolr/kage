package com.kage.card.domain.entity;

import com.kage.card.domain.enums.InvoiceStatus;
import com.kage.card.domain.valueobject.InvoiceItem;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.ConflictException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do núcleo de regra de negócio da fatura: abertura e datas, lançamento de compras,
 * ciclo de vida (aberta, fechada, paga), atraso derivado e roteamento de compra por mês.
 * Cenário base: fatura de setembro/2026, fechamento dia 10, vencimento dia 20.
 */
class InvoiceTest {

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoice = Invoice.open(UUID.randomUUID(), YearMonth.of(2026, 9), 10, 20);
    }

    private void addPurchase(String amount) {
        invoice.addItem(UUID.randomUUID(), "Compra teste", Money.of(amount), LocalDateTime.of(2026, 9, 5, 12, 0));
    }

    // ---------- open ----------

    @Test
    void open_deveNascerAbertaComTotalZeroESemItens() {
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoice.total().amount()).isEqualByComparingTo("0.00");
        assertThat(invoice.getItems()).isEmpty();
        assertThat(invoice.getPaidAt()).isNull();
        assertThat(invoice.getVersion()).isNull();
    }

    @Test
    void open_deveVencerNoMesmoMes_quandoDiaDeVencimentoMaiorQueDeFechamento() {
        assertThat(invoice.getClosingDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(invoice.getDueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void open_deveVencerNoMesSeguinte_quandoDiaDeVencimentoMenorOuIgualAoDeFechamento() {
        Invoice other = Invoice.open(UUID.randomUUID(), YearMonth.of(2026, 9), 25, 5);

        assertThat(other.getClosingDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(other.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void open_deveVirarOAnoNoVencimento_quandoFechaEmDezembro() {
        Invoice other = Invoice.open(UUID.randomUUID(), YearMonth.of(2026, 12), 25, 5);

        assertThat(other.getDueDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    // ---------- addItem / total ----------

    @Test
    void addItem_deveSomarNoTotal() {
        addPurchase("100.00");
        addPurchase("50.50");

        assertThat(invoice.getItems()).hasSize(2);
        assertThat(invoice.total().amount()).isEqualByComparingTo("150.50");
    }

    @Test
    void addItem_deveLancarConflictException_quandoCompraJaLancada() {
        UUID purchaseId = UUID.randomUUID();
        invoice.addItem(purchaseId, "Mercado", Money.of("80.00"), LocalDateTime.of(2026, 9, 3, 10, 0));

        assertThatThrownBy(() -> invoice.addItem(purchaseId, "Mercado", Money.of("80.00"), LocalDateTime.of(2026, 9, 3, 10, 0)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("já lançada");
        assertThat(invoice.total().amount()).isEqualByComparingTo("80.00");
    }

    @Test
    void addItem_deveLancarBusinessRuleException_quandoFaturaFechada() {
        invoice.close();

        assertThatThrownBy(() -> addPurchase("10.00"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não está aberta");
    }

    @Test
    void addItem_deveLancarValidationException_quandoValorZero() {
        assertThatThrownBy(() -> invoice.addItem(UUID.randomUUID(), "Compra", Money.ZERO, LocalDateTime.now()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maior que zero");
    }

    @Test
    void getItems_deveSerImutavel() {
        InvoiceItem item = InvoiceItem.create(UUID.randomUUID(), "Compra", Money.of("10.00"), LocalDateTime.now());

        assertThatThrownBy(() -> invoice.getItems().add(item))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------- close / pay ----------

    @Test
    void close_deveMudarStatusParaFechada() {
        invoice.close();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.CLOSED);
    }

    @Test
    void close_deveLancarBusinessRuleException_quandoJaFechada() {
        invoice.close();

        assertThatThrownBy(() -> invoice.close())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("abertas");
    }

    @Test
    void pay_deveMarcarComoPagaERegistrarDataDePagamento() {
        addPurchase("200.00");
        invoice.close();

        invoice.pay();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isNotNull();
    }

    @Test
    void pay_deveLancarBusinessRuleException_quandoFaturaAindaAberta() {
        assertThatThrownBy(() -> invoice.pay())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fechadas");
    }

    @Test
    void pay_deveLancarBusinessRuleException_quandoJaPaga() {
        invoice.close();
        invoice.pay();

        assertThatThrownBy(() -> invoice.pay())
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("fechadas");
    }

    // ---------- isOverdue ----------

    @Test
    void isOverdue_deveSerVerdadeiro_quandoFechadaEPassouDoVencimento() {
        invoice.close();

        assertThat(invoice.isOverdue(LocalDate.of(2026, 9, 21))).isTrue();
    }

    @Test
    void isOverdue_deveSerFalso_noProprioDiaDoVencimento() {
        invoice.close();

        assertThat(invoice.isOverdue(LocalDate.of(2026, 9, 20))).isFalse();
    }

    @Test
    void isOverdue_deveSerFalso_quandoAindaAberta() {
        assertThat(invoice.isOverdue(LocalDate.of(2026, 12, 1))).isFalse();
    }

    @Test
    void isOverdue_deveSerFalso_quandoJaPaga() {
        invoice.close();
        invoice.pay();

        assertThat(invoice.isOverdue(LocalDate.of(2026, 12, 1))).isFalse();
    }

    // ---------- referenceMonthFor ----------

    @Test
    void referenceMonthFor_deveUsarOMesDaCompra_quandoAteODiaDeFechamentoInclusive() {
        assertThat(Invoice.referenceMonthFor(LocalDate.of(2026, 9, 10), 10)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(Invoice.referenceMonthFor(LocalDate.of(2026, 9, 1), 10)).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    void referenceMonthFor_deveUsarOMesSeguinte_quandoDepoisDoFechamento() {
        assertThat(Invoice.referenceMonthFor(LocalDate.of(2026, 9, 11), 10)).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void referenceMonthFor_deveVirarOAno_quandoCompraDepoisDoFechamentoEmDezembro() {
        assertThat(Invoice.referenceMonthFor(LocalDate.of(2026, 12, 20), 10)).isEqualTo(YearMonth.of(2027, 1));
    }
}
