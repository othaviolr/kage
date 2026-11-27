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

    public static Text pixAutomaticRequested(String receiverName, String description, String frequency,
                                             BigDecimal fixedAmount, LocalDate startDate) {
        String value = fixedAmount != null ? "de " + money(fixedAmount) : "de valor variável";
        return new Text("Pedido de PIX Automático", receiverName + " pediu autorização para cobrar " + description
                + " " + frequencyLabel(frequency) + ", " + value + ", a partir de " + startDate.format(DATE)
                + ". Autorize ou recuse no app; sem resposta até lá, o pedido expira.");
    }

    public static Text pixAutomaticChargeScheduled(String receiverName, String description, BigDecimal amount,
                                                   LocalDate dueDate, int attempt) {
        if (attempt > 1) {
            return new Text("Nova tentativa do PIX Automático", "A cobrança de " + money(amount) + " de " + receiverName
                    + " (" + description + ") não foi paga por falta de saldo ou de limite. Ela será tentada de novo em "
                    + dueDate.format(DATE) + " (tentativa " + attempt + "). Se quiser, cancele até a véspera.");
        }
        return new Text("PIX Automático agendado", receiverName + " vai cobrar " + money(amount) + " em "
                + dueDate.format(DATE) + " (" + description + "). Se quiser, cancele esta cobrança até a véspera.");
    }

    private static String frequencyLabel(String frequency) {
        return switch (frequency) {
            case "WEEKLY" -> "toda semana";
            case "MONTHLY" -> "todo mês";
            case "QUARTERLY" -> "a cada 3 meses";
            case "SEMIANNUAL" -> "a cada 6 meses";
            case "ANNUAL" -> "todo ano";
            default -> frequency;
        };
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

    public static Text disputeOpened(String description, BigDecimal amount, BigDecimal creditedAmount,
                                     String creditMonth, boolean cardBlocked, boolean virtualCardDeleted) {
        StringBuilder body = new StringBuilder("Recebemos sua contestação de \"").append(description).append("\" (")
                .append(money(amount)).append("). Enquanto analisamos, essa compra não é cobrada");
        if (creditedAmount.signum() > 0 && creditMonth != null) {
            body.append(": o que já tinha sido cobrado (").append(money(creditedAmount))
                    .append(") volta como crédito na fatura de ").append(month(creditMonth));
        }
        body.append('.');
        if (cardBlocked) body.append(" Por segurança, bloqueamos seu cartão.");
        if (virtualCardDeleted) {
            body.append(" Por segurança, apagamos o cartão virtual usado nessa compra; seu cartão físico continua funcionando.");
        }
        return new Text("Contestação aberta", body.toString());
    }

    public static Text purchaseApproved(String description, BigDecimal amount, int installments,
                                        BigDecimal installmentAmount, BigDecimal availableLimit, String virtualCardNickname) {
        StringBuilder body = new StringBuilder(money(amount)).append(" em ").append(description);
        if (installments > 1) body.append(", em ").append(installments).append("x de ").append(money(installmentAmount));
        if (virtualCardNickname != null) body.append(", no cartão virtual \"").append(virtualCardNickname).append('"');
        body.append(". Limite disponível: ").append(money(availableLimit))
                .append(". Não reconhece? Bloqueie o cartão pelo app.");
        return new Text("Compra aprovada", body.toString());
    }

    public static Text purchaseDeclined(String description, BigDecimal amount, String reason, String virtualCardNickname) {
        String where = virtualCardNickname == null ? "" : " no cartão virtual \"" + virtualCardNickname + "\"";
        return new Text("Compra recusada", "A compra de " + money(amount) + " em " + description + where
                + " foi recusada: " + reason + ".");
    }

    public static Text loanContracted(BigDecimal released, int installments, BigDecimal installmentAmount,
                                      LocalDate firstDueDate) {
        return new Text("Empréstimo na sua conta", money(released) + " já estão na sua conta. São " + installments
                + " parcelas de " + money(installmentAmount) + ", a primeira em " + firstDueDate.format(DATE)
                + ", debitadas da conta. Mudou de ideia? Dá para desistir em até 7 dias, sem juros nem IOF.");
    }

    public static Text loanInstallmentPaid(int number, int installments, BigDecimal paid, BigDecimal lateCharges,
                                           BigDecimal discount, int remaining) {
        StringBuilder body = new StringBuilder("Parcela ").append(number).append('/').append(installments)
                .append(" do empréstimo paga: ").append(money(paid));
        if (discount != null && discount.signum() > 0) body.append(", com ").append(money(discount)).append(" de desconto por antecipar");
        if (lateCharges != null && lateCharges.signum() > 0) body.append(", com ").append(money(lateCharges)).append(" de multa e juros de atraso");
        body.append(". ").append(remaining == 0 ? "Era a última: seu empréstimo está quitado."
                : "Falta" + (remaining == 1 ? " 1 parcela." : "m " + remaining + " parcelas."));
        return new Text(remaining == 0 ? "Empréstimo quitado" : "Parcela do empréstimo paga", body.toString());
    }

    public static Text loanInstallmentFailed(int number, int installments, BigDecimal due, String reason) {
        return new Text("Parcela do empréstimo não foi paga", "Não conseguimos debitar a parcela " + number + "/" + installments
                + " (" + money(due) + "): " + reason + ". Deixe o saldo na conta: tentamos de novo todo dia. "
                + "Em atraso, a parcela tem multa de 2% e juros de 1% ao mês.");
    }

    public static Text loanPaidOff(BigDecimal paid, BigDecimal discount) {
        String saving = discount != null && discount.signum() > 0 ? ", com " + money(discount) + " de desconto nos juros" : "";
        return new Text("Empréstimo quitado", "Você quitou o empréstimo: " + money(paid) + saving
                + ". A carta de quitação já está disponível no app.");
    }

    public static Text loanRenegotiated(BigDecimal debt, BigDecimal downPayment, int installments,
                                        BigDecimal installmentAmount, java.time.LocalDate firstDueDate) {
        String entry = downPayment != null && downPayment.signum() > 0 ? "entrada de " + money(downPayment) + " e " : "";
        return new Text("Acordo fechado", "Sua dívida de " + money(debt) + " foi renegociada: " + entry + installments
                + " parcelas de " + money(installmentAmount) + ", a 1ª em "
                + firstDueDate.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + ". As parcelas atrasadas do contrato anterior foram encerradas.");
    }

    public static Text loanWithdrawn(BigDecimal returned) {
        return new Text("Desistência confirmada", "Cancelamos o empréstimo: " + money(returned)
                + " voltaram para o Kage, sem juros nem IOF.");
    }

    public static Text loanInstallmentDueSoon(int number, int installments, BigDecimal amount, LocalDate dueDate) {
        return new Text("Parcela do empréstimo vence logo", "A parcela " + number + "/" + installments + " ("
                + money(amount) + ") vence em " + dueDate.format(DATE) + " e é debitada da sua conta. "
                + "Deixe o saldo disponível.");
    }

    public static Text cashbackEarned(String referenceMonth, BigDecimal amount, boolean toAccount) {
        return new Text("Você ganhou cashback", "A fatura de " + month(referenceMonth) + " foi paga em dia: "
                + money(amount) + " de cashback (1% das compras) "
                + (toAccount ? "já caíram na sua conta." : "já estão abatendo a próxima fatura."));
    }

    /**
     * 80%: o limite está acabando. 100%: novas compras vão ser recusadas. Se o limite em uso está
     * abaixo do aprovado, o cliente pode subir sozinho pelo app — vale lembrar.
     */
    public static Text limitUsageAlert(int threshold, int usagePercent, BigDecimal creditLimit, BigDecimal approvedLimit,
                                       BigDecimal availableLimit) {
        String raise = approvedLimit != null && approvedLimit.compareTo(creditLimit) > 0
                ? " Se precisar, dá para subir o limite até " + money(approvedLimit) + " pelo app."
                : "";
        if (threshold >= 100) {
            return new Text("Seu limite acabou", "Todo o limite de " + money(creditLimit) + " do seu cartão está em uso: "
                    + "novas compras vão ser recusadas até você pagar a fatura ou antecipar parcelas." + raise);
        }
        return new Text("Você já usou " + usagePercent + "% do limite", "Restam " + money(availableLimit) + " de "
                + money(creditLimit) + " no seu cartão." + raise);
    }

    /** Sem fatura de transição ({@code transitionMonth} nulo): só os dias novos. */
    public static Text dueDayChanged(int dueDay, int closingDay, String transitionMonth, LocalDate transitionDueDate,
                                     Long transitionDays) {
        String days = "Suas faturas passam a vencer todo dia " + dueDay + " e a fechar no dia " + closingDay + ".";
        if (transitionMonth == null) return new Text("Vencimento da fatura alterado", days);
        return new Text("Vencimento da fatura alterado", days + " A de " + month(transitionMonth)
                + " é a de transição: vence em " + transitionDueDate.format(DATE) + ", com " + transitionDays
                + " dias de compras.");
    }

    public static Text limitOffered(BigDecimal offeredLimit, java.time.LocalDateTime expiresAt) {
        return new Text("Seu limite pode aumentar", "Pelo seu histórico de pagamentos em dia, o limite do seu cartão "
                + "pode ir para " + money(offeredLimit) + ". Aceite pelo app até "
                + expiresAt.toLocalDate().format(DATE) + ".");
    }

    public static Text cardReissued(String lastDigits, boolean oldNumberCancelled) {
        String old = oldNumberCancelled
                ? "O cartão antigo já foi cancelado; seus cartões virtuais continuam funcionando."
                : "O cartão antigo continua funcionando até você desbloquear o novo.";
        return new Text("Seu cartão novo está a caminho", "Enviamos a 2ª via do seu cartão (final " + lastDigits
                + "). Quando chegar, desbloqueie pelo app com os 4 últimos dígitos. " + old + " Limite e faturas não mudam.");
    }

    public static Text disputeResolved(String description, BigDecimal amount, boolean accepted, String note,
                                       String reversalMonth, BigDecimal interestRefunded) {
        if (accepted) {
            String refund = interestRefunded != null && interestRefunded.signum() > 0
                    ? " Também devolvemos " + money(interestRefunded) + " de juros e encargos que essa compra gerou, "
                    + "como crédito na sua fatura."
                    : "";
            return new Text("Contestação aceita", "Sua contestação de \"" + description + "\" foi aceita: o valor de "
                    + money(amount) + " não será cobrado." + refund);
        }
        String where = reversalMonth == null ? "" : " na fatura de " + month(reversalMonth);
        return new Text("Contestação negada", "Sua contestação de \"" + description + "\" foi negada (" + note
                + "). O valor de " + money(amount) + " volta a ser cobrado" + where + ".");
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

    public static Text limitIncreaseRequested(String limitType, String limitLabel, BigDecimal requestedAmount,
                                              java.time.LocalDateTime effectiveAt) {
        String when = effectiveAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm"));
        if ("PIX_NIGHT_START".equals(limitType)) {
            return new Text("Pedido para mudar a janela noturna do PIX", "Recebemos o pedido para a janela noturna do PIX"
                    + " começar às " + requestedAmount.intValue() + "h. Por segurança, isso passa a valer em " + when
                    + ". Se não foi você, cancele o pedido no app e troque a senha.");
        }
        return new Text("Pedido de aumento de limite", "Recebemos o pedido para aumentar o seu " + limitLabel + " para "
                + money(requestedAmount) + ". Por segurança, ele passa a valer em " + when
                + ". Se não foi você, cancele o pedido no app e troque a senha.");
    }

    public static Text newDeviceLogin(String userAgent, java.time.Instant at) {
        String when = at == null ? "" : " em " + java.time.format.DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm")
                .withZone(java.time.ZoneId.of("America/Sao_Paulo")).format(at);
        String device = userAgent == null || userAgent.isBlank() ? "um dispositivo novo" : "um dispositivo novo (" + userAgent + ")";
        return new Text("Novo dispositivo conectado", "Sua conta foi acessada de " + device + when
                + ", depois da confirmação pelo código enviado por SMS. Se não foi você, troque a senha e fale com o atendimento agora.");
    }

    public static Text boletoReceived(String payerName, BigDecimal paidAmount, boolean viaClearing) {
        return new Text("Boleto pago", payerName + " pagou o boleto de " + money(paidAmount)
                + (viaClearing ? " em outro banco" : "") + ". O dinheiro já está na sua conta.");
    }

    public static Text boletoPaymentMade(BigDecimal paidAmount, String beneficiaryName) {
        return new Text("Boleto pago com sucesso", "Você pagou " + money(paidAmount)
                + (beneficiaryName != null ? " para " + beneficiaryName : "") + ". Guarde o comprovante no app.");
    }

    public static Text savingsGoalReached(String boxName, BigDecimal goalAmount) {
        return new Text("Meta alcançada! 🎉",
                "Sua caixinha \"" + boxName + "\" chegou a " + money(goalAmount) + ". O dinheiro continua rendendo.");
    }

    static String money(BigDecimal amount) {
        // NumberFormat usa espaço não separável depois do "R$"; troca por espaço comum para push/e-mail
        return NumberFormat.getCurrencyInstance(PT_BR).format(amount).replace(' ', ' ');
    }

    private static String month(String referenceMonth) {
        return YearMonth.parse(referenceMonth).format(MONTH);
    }
}
