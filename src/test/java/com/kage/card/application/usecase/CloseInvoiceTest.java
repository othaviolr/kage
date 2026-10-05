package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.BusinessRuleException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.exception.ValidationException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloseInvoiceTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    InvoiceRepository invoiceRepository;

    @InjectMocks
    CloseInvoice closeInvoice;

    private Card card;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
        invoice = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        invoice.addItem(UUID.randomUUID(), "Mercado", Money.of("150.00"), LocalDateTime.of(2026, 9, 5, 12, 0));
    }

    @Test
    void execute_deveFecharFaturaAbertaEPersistir() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CloseInvoice.Output output = closeInvoice.execute(new CloseInvoice.Input(card.getId(), "2026-09"));

        assertThat(output.invoiceId()).isEqualTo(invoice.getId());
        assertThat(output.cardId()).isEqualTo(card.getId());
        assertThat(output.referenceMonth()).isEqualTo("2026-09");
        assertThat(output.status()).isEqualTo("CLOSED");
        assertThat(output.total()).isEqualByComparingTo("150.00");
        assertThat(output.closingDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(output.dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeInvoice.execute(new CloseInvoice.Input(cardId, "2026-09")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cartão não encontrado");
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    void execute_deveLancarNotFoundException_quandoNaoHaFaturaNoMes() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 8)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> closeInvoice.execute(new CloseInvoice.Input(card.getId(), "2026-08")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Fatura não encontrada");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void execute_devePropagarBusinessRuleException_quandoFaturaJaFechada() {
        invoice.close();
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardIdAndReferenceMonth(card.getId(), YearMonth.of(2026, 9)))
                .thenReturn(Optional.of(invoice));

        assertThatThrownBy(() -> closeInvoice.execute(new CloseInvoice.Input(card.getId(), "2026-09")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Somente faturas abertas");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void execute_deveLancarValidationException_quandoMesDeReferenciaInvalido() {
        assertThatThrownBy(() -> closeInvoice.execute(new CloseInvoice.Input(card.getId(), "setembro")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("yyyy-MM");
        verifyNoInteractions(cardRepository, invoiceRepository);
    }
}
