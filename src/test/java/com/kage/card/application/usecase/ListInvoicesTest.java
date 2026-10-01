package com.kage.card.application.usecase;

import com.kage.card.domain.entity.Card;
import com.kage.card.domain.entity.Invoice;
import com.kage.card.domain.repository.CardRepository;
import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListInvoicesTest {

    @Mock
    CardRepository cardRepository;

    @Mock
    InvoiceRepository invoiceRepository;

    @InjectMocks
    ListInvoices listInvoices;

    private Card card;

    @BeforeEach
    void setUp() {
        card = Card.create(UUID.randomUUID(), UUID.randomUUID(), Money.of("1000.00"), 10, 20);
    }

    @Test
    void execute_deveRetornarResumoDasFaturasNaOrdemDoRepositorio() {
        Invoice october = Invoice.open(card.getId(), YearMonth.of(2026, 10), 10, 20);
        october.addItem(UUID.randomUUID(), "Compra tardia", Money.of("50.00"), LocalDateTime.of(2026, 9, 15, 10, 0));

        Invoice september = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        september.addItem(UUID.randomUUID(), "Mercado", Money.of("150.00"), LocalDateTime.of(2026, 9, 5, 12, 0));
        september.addItem(UUID.randomUUID(), "Farmácia", Money.of("30.00"), LocalDateTime.of(2026, 9, 8, 15, 0));

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardId(card.getId())).thenReturn(List.of(october, september));

        ListInvoices.Output output = listInvoices.execute(new ListInvoices.Input(card.getId()));

        assertThat(output.cardId()).isEqualTo(card.getId());
        assertThat(output.invoices()).hasSize(2);

        ListInvoices.InvoiceSummary first = output.invoices().get(0);
        assertThat(first.invoiceId()).isEqualTo(october.getId());
        assertThat(first.referenceMonth()).isEqualTo("2026-10");
        assertThat(first.status()).isEqualTo("OPEN");
        assertThat(first.total()).isEqualByComparingTo("50.00");
        assertThat(first.itemCount()).isEqualTo(1);

        ListInvoices.InvoiceSummary second = output.invoices().get(1);
        assertThat(second.referenceMonth()).isEqualTo("2026-09");
        assertThat(second.total()).isEqualByComparingTo("180.00");
        assertThat(second.itemCount()).isEqualTo(2);
    }

    @Test
    void execute_deveRefletirStatusDaFatura() {
        Invoice closed = Invoice.open(card.getId(), YearMonth.of(2026, 9), 10, 20);
        closed.addItem(UUID.randomUUID(), "Mercado", Money.of("150.00"), LocalDateTime.of(2026, 9, 5, 12, 0));
        closed.close();

        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardId(card.getId())).thenReturn(List.of(closed));

        ListInvoices.Output output = listInvoices.execute(new ListInvoices.Input(card.getId()));

        assertThat(output.invoices().get(0).status()).isEqualTo("CLOSED");
    }

    @Test
    void execute_deveRetornarListaVazia_quandoCartaoNaoTemFaturas() {
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));
        when(invoiceRepository.findByCardId(card.getId())).thenReturn(List.of());

        ListInvoices.Output output = listInvoices.execute(new ListInvoices.Input(card.getId()));

        assertThat(output.invoices()).isEmpty();
    }

    @Test
    void execute_deveLancarNotFoundException_quandoCartaoNaoExiste() {
        UUID cardId = UUID.randomUUID();
        when(cardRepository.findById(cardId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> listInvoices.execute(new ListInvoices.Input(cardId)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Cartão não encontrado");

        verifyNoInteractions(invoiceRepository);
    }
}
