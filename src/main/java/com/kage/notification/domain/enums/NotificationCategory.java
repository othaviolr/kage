package com.kage.notification.domain.enums;

/**
 * Grupos de aviso que o cliente liga/desliga no push. Desligar só tira o push: o aviso continua na
 * caixa de entrada do app. As obrigatórias não desligam — segurança da conta, fatura (vencimento,
 * rotativo, parcelamento) e o andamento de contestação e 2ª via são coisas que o banco precisa
 * garantir que o cliente viu.
 */
public enum NotificationCategory {
    SECURITY("Segurança (senha, novo dispositivo, aumento de limite)", true),
    INVOICE("Fatura do cartão (fechamento, pagamento, rotativo, parcelamento)", true),
    CARD_SERVICES("Contestações e 2ª via do cartão", true),
    CARD_PURCHASES("Compras no cartão", false),
    PIX("PIX enviados, recebidos, estornos e cobranças agendadas", false),
    ACCOUNT("Depósitos e saques", false),
    BOLETO("Boletos recebidos e pagos", false),
    SAVINGS("Caixinhas", false);

    private final String description;
    private final boolean mandatory;

    NotificationCategory(String description, boolean mandatory) {
        this.description = description;
        this.mandatory = mandatory;
    }

    public String description() {
        return description;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
