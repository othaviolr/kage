package com.kage.boleto.domain.valueobject;

import com.kage.shared.domain.exception.ValidationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Código de um boleto de cobrança no padrão FEBRABAN.
 *
 * <pre>
 * código de barras (44): banco(3) moeda(1) DV(1) fator de vencimento(4) valor(10) campo livre(25)
 * linha digitável (47):  campo1 = banco+moeda+livre[0,5)  + DV mod 10   (10)
 *                        campo2 = livre[5,15)             + DV mod 10   (11)
 *                        campo3 = livre[15,25)            + DV mod 10   (11)
 *                        campo4 = DV geral do código de barras            (1)
 *                        campo5 = fator + valor                          (14)
 * </pre>
 *
 * O DV geral é módulo 11 (pesos 2 a 9 da direita para a esquerda; 0, 10 e 11 viram 1).
 *
 * <b>Fator de vencimento</b>: dias desde 07/10/1997. Chegou a 9999 em 21/02/2025 e, pela regra
 * nova da FEBRABAN, recomeçou em 1000 no dia 22/02/2025. Os ciclos têm 9000 dias, então um fator
 * vale para várias datas — a certa é a mais perto de hoje. Fator 0000 = boleto sem vencimento.
 */
public final class BoletoCode {

    public static final String CURRENCY_REAL = "9";
    private static final LocalDate FACTOR_BASE = LocalDate.of(1997, 10, 7);
    private static final int FACTOR_CYCLE = 9000;

    private final String barcode;

    private BoletoCode(String barcode) {
        this.barcode = barcode;
    }

    /** Aceita o código de barras (44) ou a linha digitável (47), com ou sem pontos e espaços. */
    public static BoletoCode parse(String input) {
        if (input == null) throw new ValidationException("Informe o código do boleto");
        String digits = input.replaceAll("[\\s.\\-]", "");
        if (!digits.matches("\\d+")) throw new ValidationException("O código do boleto só tem números");
        String barcode = switch (digits.length()) {
            case 44 -> digits;
            case 47 -> fromDigitableLine(digits);
            case 48 -> throw new ValidationException("Este é um código de arrecadação (contas de consumo e tributos), "
                    + "ainda não aceito: só boletos de cobrança");
            default -> throw new ValidationException("Código do boleto deve ter 44 (código de barras) ou 47 dígitos "
                    + "(linha digitável)");
        };
        if (!CURRENCY_REAL.equals(barcode.substring(3, 4))) throw new ValidationException("Boleto em moeda não aceita");
        if (barcode.charAt(4) - '0' != mod11(barcode.substring(0, 4) + barcode.substring(5))) {
            throw new ValidationException("Código do boleto inválido (dígito verificador não confere)");
        }
        return new BoletoCode(barcode);
    }

    /** Monta o código de um boleto emitido aqui. */
    public static BoletoCode build(String bankCode, LocalDate dueDate, BigDecimal amount, String freeField) {
        if (!bankCode.matches("\\d{3}")) throw new IllegalArgumentException("Banco com 3 dígitos");
        if (!freeField.matches("\\d{25}")) throw new IllegalArgumentException("Campo livre com 25 dígitos");
        long cents = amount.movePointRight(2).longValueExact();
        if (cents < 0 || cents > 99_999_999_99L) throw new ValidationException("Valor do boleto fora do limite");
        String withoutDv = bankCode + CURRENCY_REAL + factorOf(dueDate) + "%010d".formatted(cents) + freeField;
        String barcode = withoutDv.substring(0, 4) + mod11(withoutDv) + withoutDv.substring(4);
        return new BoletoCode(barcode);
    }

    private static String fromDigitableLine(String line) {
        String field1 = line.substring(0, 10), field2 = line.substring(10, 21), field3 = line.substring(21, 32);
        for (String field : new String[]{field1, field2, field3}) {
            String body = field.substring(0, field.length() - 1);
            if (field.charAt(field.length() - 1) - '0' != mod10(body)) {
                throw new ValidationException("Linha digitável inválida (confira os números digitados)");
            }
        }
        return field1.substring(0, 4) + line.charAt(32) + line.substring(33, 47)
                + field1.substring(4, 9) + field2.substring(0, 10) + field3.substring(0, 10);
    }

    public String digitableLine() {
        String free = freeField();
        String f1 = barcode.substring(0, 4) + free.substring(0, 5);
        String f2 = free.substring(5, 15);
        String f3 = free.substring(15, 25);
        return f1 + mod10(f1) + f2 + mod10(f2) + f3 + mod10(f3) + barcode.charAt(4) + barcode.substring(5, 19);
    }

    /** A linha digitável como aparece no papel: 00190.00009 01234.567890 12345.678901 1 23450000010000 */
    public String formattedDigitableLine() {
        String l = digitableLine();
        return l.substring(0, 5) + "." + l.substring(5, 10) + " " + l.substring(10, 15) + "." + l.substring(15, 21) + " "
                + l.substring(21, 26) + "." + l.substring(26, 32) + " " + l.charAt(32) + " " + l.substring(33);
    }

    public String barcode() { return barcode; }
    public String bankCode() { return barcode.substring(0, 3); }
    public String freeField() { return barcode.substring(19, 44); }

    /** Valor impresso; zero = valor a informar no pagamento. */
    public BigDecimal amount() {
        return new BigDecimal(barcode.substring(9, 19)).movePointLeft(2);
    }

    /** Vencimento pelo fator, resolvendo o ciclo pela data mais perto de {@code today}; vazio = sem vencimento. */
    public Optional<LocalDate> dueDate(LocalDate today) {
        int factor = Integer.parseInt(barcode.substring(5, 9));
        if (factor == 0) return Optional.empty();
        LocalDate candidate = FACTOR_BASE.plusDays(factor);
        while (ChronoUnit.DAYS.between(candidate, today) > FACTOR_CYCLE / 2) candidate = candidate.plusDays(FACTOR_CYCLE);
        return Optional.of(candidate);
    }

    static String factorOf(LocalDate dueDate) {
        long days = ChronoUnit.DAYS.between(FACTOR_BASE, dueDate);
        if (days < 1000) throw new ValidationException("Vencimento fora do intervalo aceito");
        long factor = days <= 9999 ? days : 1000 + (days - 10000) % FACTOR_CYCLE;
        return "%04d".formatted(factor);
    }

    /** Módulo 10: pesos 2,1,2,1... da direita; produtos acima de 9 somam os dígitos. */
    static int mod10(String digits) {
        int sum = 0, weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int product = (digits.charAt(i) - '0') * weight;
            sum += product > 9 ? product - 9 : product;
            weight = weight == 2 ? 1 : 2;
        }
        return (10 - sum % 10) % 10;
    }

    /** Módulo 11 do DV geral: pesos 2 a 9 da direita; 0, 10 e 11 viram 1. */
    static int mod11(String digits) {
        int sum = 0, weight = 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            sum += (digits.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int dv = 11 - sum % 11;
        return dv == 0 || dv == 10 || dv == 11 ? 1 : dv;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BoletoCode other && barcode.equals(other.barcode);
    }

    @Override
    public int hashCode() {
        return barcode.hashCode();
    }

    @Override
    public String toString() {
        return formattedDigitableLine();
    }
}
