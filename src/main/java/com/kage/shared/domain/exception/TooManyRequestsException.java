package com.kage.shared.domain.exception;

/** Muitas tentativas num curto período — ex.: login travado por senha errada repetida (HTTP 429). */
public class TooManyRequestsException extends DomainException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
