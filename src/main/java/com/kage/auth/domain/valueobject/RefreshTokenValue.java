package com.kage.auth.domain.valueobject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh token opaco: 256 bits aleatórios, sem significado (ao contrário do JWT, não carrega
 * dados). No banco fica só o SHA-256 — quem lê a tabela não consegue usar os tokens. SHA-256 basta
 * (não precisa de BCrypt) porque o token já tem 256 bits de entropia: não há o que adivinhar.
 */
public final class RefreshTokenValue {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenValue() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
