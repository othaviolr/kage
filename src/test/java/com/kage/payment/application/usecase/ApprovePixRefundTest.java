package com.kage.payment.application.usecase;

import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.payment.domain.enums.RefundStatus;
import com.kage.payment.domain.enums.TransactionType;
import com.kage.payment.domain.repository.PixRefundRepository;
import com.kage.payment.domain.repository.PixTransactionRepository;
import com.kage.shared.domain.exception.DomainException;
import com.kage.shared.domain.exception.NotFoundException;
import com.kage.shared.domain.valueobject.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class ApprovePixRefundTest {

    @Mock
    PixRefundRepository pixRefundRepository;

    @Mock
    PixTransactionRepository pixTransactionRepository;

    @Mock
    PixEventPublisher pixEventPublisher;

    @InjectMocks
    ApprovePixRefund approvePixRefund;

    private PixTransaction original;
    private PixRefund refund;

    @BeforeEach
    void setUp() {
        original = PixTransaction.create(UUID.randomUUID(), "chave", Money.of("100.00"), null, TransactionType.IMMEDIATE, null);
        original.startProcessing(UUID.randomUUID());
        original.complete();
        refund = PixRefund.create(original.getTransactionId(), "produto não entregue", Money.of("100.00"));
    }

    @Test
    void execute_deveAprovarSemConcluir_ePedirDevolucaoAoAccount() {
        when(pixRefundRepository.findById(refund.getRefundId())).thenReturn(Optional.of(refund));
        when(pixTransactionRepository.findById(original.getTransactionId())).thenReturn(Optional.of(original));

        approvePixRefund.execute(refund.getRefundId(), "admin");

        // COMPLETED só quando o Account confirmar que o dinheiro voltou
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
        verify(pixRefundRepository).save(refund);
        verify(pixEventPublisher).publishRefundApproved(refund, original);
    }

    @Test
    void execute_naoDevePublicar_quandoEstornoNaoEstaPendente() {
        refund.reject("admin");
        when(pixRefundRepository.findById(refund.getRefundId())).thenReturn(Optional.of(refund));
        when(pixTransactionRepository.findById(original.getTransactionId())).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> approvePixRefund.execute(refund.getRefundId(), "admin"))
                .isInstanceOf(DomainException.class);

        verify(pixRefundRepository, never()).save(any());
        verifyNoInteractions(pixEventPublisher);
    }

    @Test
    void execute_deveLancarNotFound_quandoEstornoNaoExiste() {
        UUID refundId = UUID.randomUUID();
        when(pixRefundRepository.findById(refundId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approvePixRefund.execute(refundId, "admin"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(pixEventPublisher);
    }
}
