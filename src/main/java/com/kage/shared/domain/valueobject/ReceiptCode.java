package com.kage.shared.domain.valueobject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Código de autenticação do comprovante: SHA-256 dos dados que importam (quem, quanto, quando, qual
 * transação), em blocos de 4 para ser lido em voz alta no atendimento. O banco recalcula a partir da
 * transação: um comprovante com o valor ou a data editados não bate com o código.
 */
public final class ReceiptCode {

    private ReceiptCode() {
    }

    public static String of(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) canonical.append(field).append('|');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().withUpperCase().formatHex(digest).substring(0, 32);
            return String.join("-", hex.split("(?<=\\G.{4})"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
