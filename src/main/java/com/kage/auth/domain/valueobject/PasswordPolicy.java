package com.kage.auth.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

/**
 * Regras mínimas de senha: 8 a 72 caracteres (72 é o limite que o BCrypt de fato considera), com
 * letra e número, e sem ser derivada do próprio CPF.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 72;

    private PasswordPolicy() {
    }

    public static void validate(String rawPassword, String cpfDigits) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH) {
            throw new ValidationException("Senha deve ter pelo menos " + MIN_LENGTH + " caracteres");
        }
        if (rawPassword.length() > MAX_LENGTH) {
            throw new ValidationException("Senha deve ter no máximo " + MAX_LENGTH + " caracteres");
        }
        if (!rawPassword.matches(".*[A-Za-z].*") || !rawPassword.matches(".*\\d.*")) {
            throw new ValidationException("Senha deve conter letras e números");
        }
        String passwordDigits = rawPassword.replaceAll("\\D", "");
        if (cpfDigits != null && passwordDigits.length() >= 6 && cpfDigits.contains(passwordDigits)) {
            throw new ValidationException("Senha não pode conter sequência do CPF");
        }
    }
}
