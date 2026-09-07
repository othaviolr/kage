package com.kage.payment.application.usecase;

public interface PixEventPublisher {
    void publishPixSent(SendPix.Output output);
}
