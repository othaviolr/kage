package com.kage.shared.domain.exception;

/** Credenciais ausentes ou inválidas (HTTP 401). */
public class UnauthorizedException extends DomainException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
