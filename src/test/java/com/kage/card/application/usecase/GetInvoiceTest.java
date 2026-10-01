package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetInvoiceTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    InvoiceRepository invoiceRepository;

    @InjectMocks
    GetInvoice getInvoice;

    private Card card;
    private final LocalDateTime purchasedAt = LocalDateTime.of(2026, 9, 5, 12, 0);

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void execute_deveRetornarFaturaComItensETotal() {
        Invoice invoice = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        UUID purchaseId = UUID.randomUUID();
        invoice.addItem(purchaseId, "Mercado", Money.of("150.00"), purchasedAt);
        invoice.addItem(UUID.randomUUID(), "Farmácia", Money.of("30.00"), purchasedAt.plusDays(2));

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(invoice));

        GetInvoice.Output output = getInvoice.execute(new GetInvoice.Input(card.getId(), "2026-09"));

        assertThat(output.invoiceId()).isEqualTo(invoice.getId());
        assertThat(output.cardId()).isEqualTo(card.getId());
        assertThat(output.referenceMonth()).isEqualTo("2026-09");
        assertThat(output.closingDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(output.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(output.status()).isEqualTo("OPEN");
        assertThat(output.total()).isEqualByComparingTo("180.00");
        assertThat(output.paidAt()).isNull();
        assertThat(output.items()).hasSize(2);
        assertThat(output.items().get(0).purchaseId()).isEqualTo(purchaseId);
        assertThat(output.items().get(0).description()).isEqualTo("Mercado");
        assertThat(output.items().get(0).amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void execute_deveRetornarFaturaSemItens_quandoAindaNaoHaLancamentos() {
        Invoice invoice = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(invoice));

        GetInvoice.Output output = getInvoice.execute(new GetInvoice.Input(card.getId(), "2026-09"));

        assertThat(output.items()).isEmpty();
        assertThat(output.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(cardId, "2026-09")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cartão não encontrado");

        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoNaoHaFaturaNoMes() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(card.getId(), "2026-09")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Fatura não encontrada");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "2026-13", "2026/09", "09-2026", ""})
    void execute_deveLancarValidationException_quandoMesDeReferenciaInvalido(String referenceMonth) {
        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(card.getId(), referenceMonth)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("yyyy-MM");

        verifyNoInteractions(cardRepository, invoiceRepository);
    }

    @Test
    void execute_deveLancarValidationException_quandoMesDeReferenciaNulo() {
        assertThatThrownBy(() -> getInvoice.execute(new GetInvoice.Input(card.getId(), null)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(cardRepository, invoiceRepository);
    }
}
