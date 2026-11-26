package com.kage.notification.domain.enums;

/**
 * Grupos de aviso que o cliente liga/desliga no push. Desligar só tira o push: o aviso continua na
 * caixa de entrada do app. As obrigatórias não desligam — segurança da conta, fatura (vencimento,
 * rotativo, parcelamento) e o andamento de contestação e 2ª via são coisas que o banco precisa
 * garantir que o cliente viu.
 */
public enum NotificationCategory {
    SECURITY("Segurança (senha, novo dispositivo, aumento de limite)", "segurança", true),
    INVOICE("Fatura do cartão (fechamento, pagamento, rotativo, parcelamento)", "fatura", true),
    CARD_SERVICES("Contestações e 2ª via do cartão", "contestação e 2ª via", true),
    CARD_PURCHASES("Compras no cartão", "compras no cartão", false),
    PIX("PIX enviados, recebidos, estornos e cobranças agendadas", "PIX", false),
    ACCOUNT("Depósitos e saques", "depósitos e saques", false),
    BOLETO("Boletos recebidos e pagos", "boletos", false),
    SAVINGS("Caixinhas", "caixinhas", false);

    private final String description;
    private final String label;
    private final boolean mandatory;

    NotificationCategory(String description, String label, boolean mandatory) {
        this.description = description;
        this.label = label;
        this.mandatory = mandatory;
    }

    /** Nome curto, para frases ("3 de PIX, 2 de compras no cartão"). */
    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean isMandatory() {
        return mandatory;
    }
}
