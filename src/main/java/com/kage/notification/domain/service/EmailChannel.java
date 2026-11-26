package com.kage.notification.domain.service;

/** Envio de e-mail (SES, SMTP...). A implementação padrão só registra no log. */
public interface EmailChannel {

    /** @param attachment opcional */
    record Attachment(String fileName, String contentType, byte[] content) {}

    record Email(String to, String subject, String body, Attachment attachment) {}

    void send(Email email);
}
