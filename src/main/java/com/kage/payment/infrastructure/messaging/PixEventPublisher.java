package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.SendPix;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class PixEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public PixEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishPixSent(SendPix.Output output) {
        PixSentEvent event = new PixSentEvent(output.transactionId(), output.sourceAccountId(),
                output.targetPixKey(), output.targetAccountId(), output.amount(), output.status(), output.e2eId(), output.createdAt());

        rabbitTemplate.convertAndSend("pix.exchange", "pix.sent", event);
    }
}