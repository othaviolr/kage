package com.kage.card.infrastructure.security;

import com.kage.card.domain.service.CardNumberService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Número = BIN (6) + 9 dígitos do HMAC-SHA256 do id + dígito de Luhn. CVV = 3 dígitos do HMAC do
 * número com a validade (como o CVV de verdade, que o HSM do emissor calcula e ninguém guarda). Hash
 * para busca = HMAC do número, com outro rótulo: quem lê o banco não tem como voltar ao número.
 */
public class HmacCardNumberService implements CardNumberService {

    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final String bin;

    public HmacCardNumberService(String secret, String bin) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("kage.cards.secret precisa ter pelo menos " + MIN_SECRET_BYTES + " bytes");
        }
        if (bin == null || !bin.matches("\\d{6}")) throw new IllegalArgumentException("kage.cards.bin precisa ter 6 dígitos");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.bin = bin;
    }

    @Override
    public String numberFor(UUID virtualCardId) {
        String body = bin + digits("PAN:" + virtualCardId, 9);
        return body + luhnDigit(body);
    }

    @Override
    public String cvvFor(String number, YearMonth expiry) {
        return digits("CVV:" + number + ":" + expiry, 3);
    }

    @Override
    public String hash(String number) {
        return HexFormat.of().formatHex(hmac("LOOKUP:" + number));
    }

    /** Dígito que torna o número válido no algoritmo de Luhn (o que a maquininha e o site checam). */
    static int luhnDigit(String body) {
        int sum = 0;
        boolean twice = true; // da direita para a esquerda, começando pelo vizinho do dígito verificador
        for (int i = body.length() - 1; i >= 0; i--) {
            int d = body.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10;
    }

    static boolean isLuhnValid(String number) {
        return number.matches("\\d{2,19}")
                && luhnDigit(number.substring(0, number.length() - 1)) == number.charAt(number.length() - 1) - '0';
    }

    private String digits(String label, int count) {
        BigInteger value = new BigInteger(1, hmac(label)).mod(BigInteger.TEN.pow(count));
        return String.format("%0" + count + "d", value);
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC indisponível", e);
        }
    }
}
