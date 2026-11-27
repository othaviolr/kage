package com.kage.card.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;

/**
 * PIX copia e cola (BR Code, padrão EMV do Banco Central): o texto que vira o QR Code e que qualquer
 * banco lê para pagar. Cada campo é ID (2 dígitos) + tamanho (2 dígitos) + valor; o último é o CRC16
 * (CCITT-FALSE) de tudo o que vem antes, inclusive o "6304" dele.
 *
 * Campos: 00 versão; 01 "12" = uso único (a cobrança da fatura não deve ser paga duas vezes); 26 conta
 * do recebedor (GUI br.gov.bcb.pix + chave); 52 categoria; 53 moeda (986 = real); 54 valor; 58 país;
 * 59 nome; 60 cidade; 62 dados adicionais (05 = txid, que identifica o pagamento na volta).
 */
public final class PixBrCode {

    private static final String GUI = "br.gov.bcb.pix";
    private static final int MAX_NAME = 25;
    private static final int MAX_CITY = 15;
    private static final int MAX_TXID = 25;

    private PixBrCode() {
    }

    /**
     * @param amount     nulo = o pagador digita o valor
     * @param singleUse  marca o código como de uso único (campo 01 = 12)
     * @param txid       até 25 letras e números; nulo = "***" (sem identificador)
     */
    public static String of(String pixKey, BigDecimal amount, String merchantName, String city, String txid,
                            boolean singleUse) {
        if (pixKey == null || pixKey.isBlank()) throw new IllegalArgumentException("Chave PIX obrigatória");
        String id = txid == null ? "***" : txid;
        if (txid != null && !txid.matches("[A-Za-z0-9]{1," + MAX_TXID + "}")) {
            throw new IllegalArgumentException("txid com até " + MAX_TXID + " letras e números");
        }
        StringBuilder payload = new StringBuilder()
                .append(field("00", "01"));
        if (singleUse) payload.append(field("01", "12"));
        payload.append(field("26", field("00", GUI) + field("01", pixKey)))
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

    static String field(String id, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("Campo " + id + " passa de 99 caracteres");
        return id + "%02d".formatted(value.length()) + value;
    }

    /** CRC16/CCITT-FALSE (polinômio 0x1021, início 0xFFFF), em 4 dígitos hexadecimais maiúsculos. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
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
