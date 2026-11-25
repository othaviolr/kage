package com.kage.boleto.application.usecase;

import com.kage.boleto.domain.entity.Boleto;
import com.kage.boleto.domain.repository.BoletoPaymentRepository;

/** Eventos de boleto. Grava no outbox: chamar dentro da transação. */
public interface BoletoEventPublisher {

    /** Boleto emitido aqui foi pago (por cliente do Kage ou pela compensação): avisa o beneficiário. */
    void publishBoletoPaid(Boleto boleto);

    /** Cliente do Kage pagou um boleto: avisa o pagador. */
    void publishPaymentMade(BoletoPaymentRepository.Payment payment);
}
