package com.kage.shared.domain.pix;

import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PIX copia e cola (BR Code, padrão EMV do Banco Central): o texto que vira o QR Code e que qualquer
 * banco lê para pagar. Cada campo é ID (2 dígitos) + tamanho (2 dígitos) + valor; o último é o CRC16
 * (CCITT-FALSE) de tudo o que vem antes, inclusive o "6304" dele.
 *
 * Campos: 00 versão; 01 "12" = uso único (a cobrança da fatura não deve ser paga duas vezes); 26 conta
 * do recebedor (GUI br.gov.bcb.pix + chave); 52 categoria; 53 moeda (986 = real); 54 valor; 58 país;
 * 59 nome; 60 cidade; 62 dados adicionais (05 = txid, que identifica o pagamento na volta).
 *
 * {@link #of} monta o código; {@link #parse} faz o caminho de volta, lendo o que o cliente colou para pagar.
 */
public final class PixBrCode {

    private static final String GUI = "br.gov.bcb.pix";
    private static final int MAX_NAME = 25;
    private static final int MAX_CITY = 15;
    private static final int MAX_TXID = 25;
    static final String INVALID = "Código PIX inválido: confira se copiou o código inteiro";

    /**
     * O que o copia e cola diz.
     *
     * @param amount nulo = quem paga digita o valor
     * @param txid   nulo quando o código não tem identificador ("***")
     * @param info   mensagem do recebedor (campo 26, subcampo 02), se houver
     */
    public record Parsed(String pixKey, BigDecimal amount, String merchantName, String city, String txid,
                         boolean singleUse, String info) {}

    private PixBrCode() {
    }

    /**
     * @param amount     nulo = o pagador digita o valor
     * @param singleUse  marca o código como de uso único (campo 01 = 12)
     * @param txid       até 25 letras e números; nulo = "***" (sem identificador)
     */
    public static String of(String pixKey, BigDecimal amount, String merchantName, String city, String txid,
                            boolean singleUse) {
        return of(pixKey, amount, merchantName, city, txid, singleUse, null);
    }

    /**
     * @param info mensagem para quem paga (campo 26, subcampo 02), sem acento; nula = sem mensagem. Cabe
     *             o que sobra dos 99 caracteres da conta do recebedor depois da chave.
     */
    public static String of(String pixKey, BigDecimal amount, String merchantName, String city, String txid,
                            boolean singleUse, String info) {
        if (pixKey == null || pixKey.isBlank()) throw new IllegalArgumentException("Chave PIX obrigatória");
        String id = txid == null ? "***" : txid;
        if (txid != null && !txid.matches("[A-Za-z0-9]{1," + MAX_TXID + "}")) {
            throw new IllegalArgumentException("txid com até " + MAX_TXID + " letras e números");
        }
        StringBuilder payload = new StringBuilder()
                .append(field("00", "01"));
        if (singleUse) payload.append(field("01", "12"));
        String account = field("00", GUI) + field("01", pixKey);
        if (info != null && !info.isBlank()) account += field("02", ascii(info, 99));
        payload.append(field("26", account))
                .append(field("52", "0000"))
                .append(field("53", "986"));
        if (amount != null) payload.append(field("54", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        payload.append(field("58", "BR"))
                .append(field("59", ascii(merchantName, MAX_NAME)))
                .append(field("60", ascii(city, MAX_CITY)))
                .append(field("62", field("05", id)))
                .append("6304");
        return payload + crc16(payload.toString());
    }

    /**
     * Lê um PIX copia e cola estático. Confere o CRC (o código veio inteiro e sem alteração), a versão,
     * a moeda e o formato de cada campo.
     *
     * @throws ValidationException se o código não é um PIX válido, é de QR Code dinâmico (traz uma URL
     *                             em vez da chave) ou não é em reais
     */
    public static Parsed parse(String code) {
        if (code == null || code.isBlank()) throw new ValidationException("Cole o código PIX copia e cola");
        String text = code.strip();
        if (text.length() < 8 || !text.startsWith("6304", text.length() - 8)) throw new ValidationException(INVALID);
        String crc = text.substring(text.length() - 4).toUpperCase(Locale.ROOT);
        if (!crc.equals(crc16(text.substring(0, text.length() - 4)))) throw new ValidationException(INVALID);

        Map<String, String> fields = fields(text.substring(0, text.length() - 8));
        if (!"01".equals(fields.get("00"))) throw new ValidationException(INVALID);

        // a conta do recebedor pode vir em qualquer ID de 26 a 51; a do PIX é a do GUI br.gov.bcb.pix
        Map<String, String> account = null;
        for (int id = 26; id <= 51 && account == null; id++) {
            String value = fields.get(String.valueOf(id));
            if (value == null) continue;
            Map<String, String> sub = fields(value);
            if (GUI.equalsIgnoreCase(sub.get("00"))) account = sub;
        }
        if (account == null) throw new ValidationException("Este código não é de PIX");
        String key = account.get("01");
        if (key == null || key.isBlank()) {
            throw new ValidationException(account.containsKey("25")
                    ? "PIX de QR Code dinâmico ainda não é aceito no Kage: peça um código com a chave"
                    : INVALID);
        }

        String currency = fields.get("53");
        if (currency != null && !currency.equals("986")) throw new ValidationException("Só é possível pagar PIX em reais");

        BigDecimal amount = null;
        String rawAmount = fields.get("54");
        if (rawAmount != null) {
            if (!rawAmount.matches("\\d{1,10}(\\.\\d{1,2})?")) throw new ValidationException(INVALID);
            amount = new BigDecimal(rawAmount).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() == 0) amount = null; // 0.00 = sem valor definido
        }

        String txid = null;
        String additional = fields.get("62");
        if (additional != null) {
            String id = fields(additional).get("05");
            if (id != null && !id.isBlank() && !id.equals("***")) txid = id;
        }

        return new Parsed(key.strip(), amount, fields.get("59"), fields.get("60"), txid, "12".equals(fields.get("01")),
                account.get("02"));
    }

    /** ID (2 dígitos) + tamanho (2 dígitos) + valor, um atrás do outro; vale o primeiro de cada ID. */
    private static Map<String, String> fields(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        int i = 0;
        while (i < payload.length()) {
            if (i + 4 > payload.length()) throw new ValidationException(INVALID);
            String id = payload.substring(i, i + 2);
            String size = payload.substring(i + 2, i + 4);
            if (!id.matches("\\d{2}") || !size.matches("\\d{2}")) throw new ValidationException(INVALID);
            int end = i + 4 + Integer.parseInt(size);
            if (end > payload.length()) throw new ValidationException(INVALID);
            fields.putIfAbsent(id, payload.substring(i + 4, end));
            i = end;
        }
        return fields;
    }

    /** Quantos caracteres de mensagem cabem junto dessa chave (a conta do recebedor tem até 99). */
    public static int maxInfoLength(String pixKey) {
        return Math.max(0, 99 - field("00", GUI).length() - 4 - pixKey.length() - 4);
    }

    static String field(String id, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("Campo " + id + " passa de 99 caracteres");
        return id + "%02d".formatted(value.length()) + value;
    }

    /** CRC16/CCITT-FALSE (polinômio 0x1021, início 0xFFFF), em 4 dígitos hexadecimais maiúsculos. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return "%04X".formatted(crc);
    }

    /** O BR Code só aceita ASCII: tira os acentos e corta no tamanho do campo. */
    static String ascii(String text, int max) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "").trim();
        return plain.length() > max ? plain.substring(0, max) : plain;
    }
}
