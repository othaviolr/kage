package com.kage.notification.application.usecase;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Textos dos avisos, num lugar só (o tom do app mora aqui). Valores e datas no formato brasileiro. */
public final class NotificationTemplates {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR"); // Locale.of é Java 19+; o projeto é 17
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM/yyyy");

    private NotificationTemplates() {
    }

    public record Text(String title, String message) {}

    public static Text depositReceived(BigDecimal amount) {
        return new Text("Depósito recebido", "Entraram " + money(amount) + " na sua conta.");
    }

    public static Text withdrawalMade(BigDecimal amount) {
        return new Text("Saque realizado", "Você sacou " + money(amount) + ".");
    }

    public static Text pixSent(BigDecimal amount) {
        return new Text("PIX enviado", "Seu PIX de " + money(amount) + " foi concluído.");
    }

    public static Text pixReceived(BigDecimal amount) {
        return new Text("Você recebeu um PIX", "Caíram " + money(amount) + " na sua conta.");
    }

    public static Text pixFailed(BigDecimal amount, String reason) {
        return new Text("PIX não concluído", "Seu PIX de " + money(amount) + " não foi concluído: " + reason
                + ". Nenhum valor saiu da sua conta.");
    }

    public static Text scheduledPixFailed(BigDecimal amount, LocalDate scheduledDate, String reason) {
        return new Text("Seu PIX agendado não foi realizado", "O PIX de " + money(amount) + " agendado para "
                + scheduledDate.format(DATE) + " não foi realizado: " + reason + ". Nenhum valor saiu da sua conta.");
    }

    public static Text refundSent(BigDecimal amount) {
        return new Text("Estorno de PIX", "Um PIX que você recebeu foi estornado: " + money(amount) + " foram devolvidos.");
    }

    public static Text refundReceived(BigDecimal amount) {
        return new Text("Estorno recebido", "Seu estorno de " + money(amount) + " foi concluído e o valor voltou para a sua conta.");
    }

    public static Text refundFailed(BigDecimal amount, String reason) {
        return new Text("Estorno não concluído", "O estorno de " + money(amount) + " não pôde ser concluído: " + reason + ".");
    }

    public static Text invoiceClosed(String referenceMonth, BigDecimal total, LocalDate dueDate) {
        return new Text("Sua fatura fechou", "A fatura de " + month(referenceMonth) + " fechou em " + money(total)
                + " e vence em " + dueDate.format(DATE) + ".");
    }

    public static Text invoicePaid(String referenceMonth, BigDecimal amount) {
        return new Text("Fatura paga", "Recebemos o pagamento de " + money(amount) + " da fatura de " + month(referenceMonth)
                + ". Seu limite foi liberado.");
    }

    public static Text invoicePartiallyPaid(String referenceMonth, BigDecimal amountPaid, BigDecimal remaining,
                                            BigDecimal minimumPayment, BigDecimal totalPaid) {
        String warning = totalPaid.compareTo(minimumPayment) < 0
                ? " Atenção: você ainda não chegou ao pagamento mínimo de " + money(minimumPayment)
                  + " — abaixo dele há multa e juros de mora."
                : " O que não for pago até o próximo fechamento entra no rotativo, com juros.";
        return new Text("Pagamento recebido", "Recebemos " + money(amountPaid) + " da fatura de " + month(referenceMonth)
                + ". Faltam " + money(remaining) + "." + warning);
    }

    public static Text invoiceRolledOver(String fromMonth, String toMonth, BigDecimal carried, BigDecimal charges, boolean late) {
        return new Text("Saldo da fatura foi para o rotativo", "O saldo de " + money(carried) + " da fatura de "
                + month(fromMonth) + " foi para a fatura de " + month(toMonth) + " com " + money(charges)
                + (late ? " de juros, multa e mora por atraso." : " de juros do rotativo.")
                + " Pagar o total evita novos juros.");
    }

    public static Text invoiceFinanced(String referenceMonth, int installments, BigDecimal installmentAmount,
                                       BigDecimal total, boolean automatic) {
        String body = "A fatura de " + month(referenceMonth) + " foi parcelada em " + installments + "x de "
                + money(installmentAmount) + " (total " + money(total) + "). As parcelas vêm nas próximas faturas.";
        return automatic
                ? new Text("Sua fatura foi parcelada", "O saldo do rotativo não foi pago e, pela regra do Banco Central, "
                        + "não pode continuar no rotativo. " + body)
                : new Text("Parcelamento contratado", body);
    }

    public static Text autoDebitFailed(String referenceMonth, String reason) {
        return new Text("Não conseguimos pagar sua fatura", "O débito automático da fatura de " + month(referenceMonth)
                + " não passou (" + reason + "). Vamos tentar de novo amanhã, ou você pode pagar pelo app.");
    }

    /** Alerta de segurança: o texto tem que dizer o que fazer se não foi o cliente. */
    public static Text passwordChanged(boolean viaRecovery) {
        return new Text("Sua senha foi alterada",
                (viaRecovery ? "Sua senha foi redefinida pelo link de recuperação e todos os dispositivos foram desconectados."
                        : "Sua senha foi alterada e os outros dispositivos foram desconectados.")
                        + " Se não foi você, fale com o atendimento agora.");
    }

    static String money(BigDecimal amount) {
        // NumberFormat usa espaço não separável depois do "R$"; troca por espaço comum para push/e-mail
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    private static String month(String referenceMonth) {
        return YearMonth.parse(referenceMonth).format(MONTH);
    }
}
