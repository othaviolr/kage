package com.kage.boleto.infrastructure.messaging;

import com.kage.shared.domain.DomainTime;
import com.kage.boleto.application.usecase.BoletoEventPublisher;
import com.kage.boleto.domain.entity.Boleto;
import com.kage.boleto.domain.repository.BoletoPaymentRepository;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;


public class OutboxBoletoEventPublisher implements BoletoEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxBoletoEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishBoletoPaid(Boleto b) {
        outbox.publish(BoletoEvents.EXCHANGE, BoletoEvents.BOLETO_PAID, new BoletoEvents.BoletoPaidEvent(
                b.getId(), b.getBeneficiaryAccountId(), b.getPayerName(), b.getAmount().amount(), b.getPaidAmount().amount(),
                b.getPaidVia(), DomainTime.instant()));
    }

    @Override
    public void publishPaymentMade(BoletoPaymentRepository.Payment p) {
        outbox.publish(BoletoEvents.EXCHANGE, BoletoEvents.PAYMENT_MADE, new BoletoEvents.PaymentMadeEvent(
                p.id(), p.payerAccountId(), p.kind().name(), p.beneficiaryName(), p.paidAmount(), DomainTime.instant()));
    }
}
