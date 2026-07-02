package com.kage.shared.domain.exception;

import java.util.List;

public class ValidationException extends DomainException {

    private final List<FieldError> fieldErrors;

    public ValidationException(String message) {
        super(message);
        this.fieldErrors = List.of();
    }

    public ValidationException(String message, List<FieldError> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String field, String message) {
    }
}