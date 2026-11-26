package com.kage.auth.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Identificador do aparelho (header {@code X-Device-Id}): o app gera um valor aleatório na instalação e
 * manda em todo login. No banco fica só o SHA-256 — quem lê a tabela não consegue se passar por um
 * aparelho confiável.
 */
public final class DeviceId {

    public static final String HEADER = "X-Device-Id";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceId() {
    }

    /** SHA-256 do identificador, ou nulo se o app não mandou (aí todo login pede código). */
    public static String hashOrNull(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        if (!VALID.matcher(deviceId).matches()) {
            throw new ValidationException(HEADER + " inválido: de 16 a 128 letras, números, '-' ou '_'");
        }
        return RefreshTokenValue.hash(deviceId);
    }

    /** Código de 6 dígitos para o SMS. */
    public static String newLoginCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** Hash do código amarrado ao desafio: o mesmo código em outro desafio não bate. */
    public static String hashCode(java.util.UUID challengeId, String code) {
        return RefreshTokenValue.hash(challengeId + ":" + code);
    }
}
