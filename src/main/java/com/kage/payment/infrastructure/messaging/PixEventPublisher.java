package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.SendPix;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class PixEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public PixEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishPixSent(SendPix.Output output) {
        rabbitTemplate.convertAndSend("pix.exchange", "pix.sent", output);
    }
}