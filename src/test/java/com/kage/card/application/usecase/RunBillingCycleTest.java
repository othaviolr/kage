package com.kage.card.application.usecase;

import com.kage.card.domain.repository.InvoiceRepository;
import com.kage.card.domain.repository.InvoiceRepository.InvoiceRef;
import com.kage.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunBillingCycleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 12, 20);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    @Mock InvoiceRepository invoiceRepository;
    @Mock CloseInvoice closeInvoice;
    @Mock PayInvoice payInvoice;
    @Mock CardEventPublisher eventPublisher;

    @InjectMocks RunBillingCycle cycle;

    @Test
    void execute_deveFecharAntesDeCobrar() {
        UUID card = UUID.randomUUID();
        when(invoiceRepository.findOpenWithClosingDateBefore(TODAY)).thenReturn(List.of(new InvoiceRef(card, NOV)));
        when(invoiceRepository.findClosedDueUntilWithAutoDebit(TODAY)).thenReturn(List.of(new InvoiceRef(card, NOV)));

        RunBillingCycle.Output output = cycle.execute(TODAY);

        // uma fatura cujo corte e vencimento já passaram é fechada e paga na mesma rodada
        InOrder order = inOrder(closeInvoice, payInvoice);
        order.verify(closeInvoice).execute(new CloseInvoice.Input(card, "2026-11"));
        order.verify(payInvoice).execute(new PayInvoice.Input(card, "2026-11"));
        assertThat(output.invoicesClosed()).isEqualTo(1);
        assertThat(output.invoicesPaid()).isEqualTo(1);
        assertThat(output.failures()).isEmpty();
    }

    @Test
    void execute_deveSeguirOLote_quandoUmaFaturaFalha() {
        UUID semSaldo = UUID.randomUUID();
        UUID comSaldo = UUID.randomUUID();
        when(invoiceRepository.findOpenWithClosingDateBefore(TODAY)).thenReturn(List.of());
        when(invoiceRepository.findClosedDueUntilWithAutoDebit(TODAY))
                .thenReturn(List.of(new InvoiceRef(semSaldo, NOV), new InvoiceRef(comSaldo, NOV)));
        when(payInvoice.execute(new PayInvoice.Input(semSaldo, "2026-11")))
                .thenThrow(new BusinessRuleException("Saldo disponível insuficiente"));

        RunBillingCycle.Output output = cycle.execute(TODAY);

        verify(payInvoice).execute(new PayInvoice.Input(comSaldo, "2026-11"));
        // só quem não pagou recebe o aviso de débito recusado
        verify(eventPublisher).publishAutoDebitFailed(semSaldo, "2026-11", "Saldo disponível insuficiente");
        verify(eventPublisher, never()).publishAutoDebitFailed(eq(comSaldo), any(), any());
        assertThat(output.invoicesPaid()).isEqualTo(1);
        assertThat(output.failures()).singleElement().satisfies(f -> {
            assertThat(f.cardId()).isEqualTo(semSaldo);
            assertThat(f.step()).isEqualTo("AUTO_DEBIT");
            assertThat(f.reason()).contains("Saldo");
        });
    }

    @Test
    void execute_deveInterromper_quandoErroDeInfraestrutura() {
        // banco fora do ar não é "falha de uma fatura": o job para e roda de novo no próximo agendamento
        when(invoiceRepository.findOpenWithClosingDateBefore(TODAY)).thenReturn(List.of(new InvoiceRef(UUID.randomUUID(), NOV)));
        when(closeInvoice.execute(any())).thenThrow(new DataAccessResourceFailureException("conexão recusada"));

        assertThatThrownBy(() -> cycle.execute(TODAY)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
