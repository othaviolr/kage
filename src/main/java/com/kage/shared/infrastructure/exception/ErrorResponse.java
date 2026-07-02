package com.kage.shared.infrastructure.exception;

import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(int status, String error, String message, String path, LocalDateTime timestamp, List<FieldErrorResponse> fieldErrors) {
    public record FieldErrorResponse(String field, String message) {}

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, LocalDateTime.now(), List.of());
    }

    public static ErrorResponse of(int status, String error, String message, String path, List<FieldErrorResponse> fieldErrors) {
        return new ErrorResponse(status, error, message, path, LocalDateTime.now(), fieldErrors);
    }
}