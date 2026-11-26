package com.kage.shared.domain.valueobject;

/**
 * CPF mascarado no padrão que os bancos usam em comprovantes e telas de confirmação
 * ({@code ***.456.789-**}): dá para reconhecer a pessoa sem expor o documento inteiro. CNPJ é dado
 * público e sai inteiro.
 */
public final class DocumentMask {

    private DocumentMask() {
    }

    public static String mask(String document) {
        if (document == null) return null;
        String digits = document.replaceAll("\\D", "");
        if (digits.length() == 11) return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**";
        if (digits.length() == 14) {
            return digits.substring(0, 2) + "." + digits.substring(2, 5) + "." + digits.substring(5, 8) + "/"
                    + digits.substring(8, 12) + "-" + digits.substring(12);
        }
        return digits;
    }

    /** Chave PIX no comprovante: CPF mascarado; e-mail, telefone e aleatória como foram digitados. */
    public static String maskPixKey(String key) {
        if (key == null) return null;
        String digits = key.replaceAll("\\D", "");
        boolean looksLikeCpf = digits.length() == 11 && key.replaceAll("[\\d.\\-]", "").isEmpty();
        return looksLikeCpf ? mask(key) : key;
    }
}
