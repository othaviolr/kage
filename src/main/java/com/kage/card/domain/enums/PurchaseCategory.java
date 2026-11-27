package com.kage.card.domain.enums;

/**
 * Categoria da compra, para o resumo de gastos e o filtro do extrato. Vem do MCC (Merchant Category
 * Code, ISO 18245) que a bandeira manda na autorização — ver
 * {@link com.kage.card.domain.service.MerchantCategoryPolicy} — e o cliente pode trocar a de uma compra.
 */
public enum PurchaseCategory {
    SUPERMARKET("Mercado"),
    RESTAURANTS("Restaurantes e delivery"),
    TRANSPORT("Transporte"),
    TRAVEL("Viagem"),
    HEALTH("Saúde"),
    EDUCATION("Educação"),
    ENTERTAINMENT("Lazer e assinaturas"),
    SHOPPING("Compras"),
    HOME("Casa"),
    SERVICES("Serviços"),
    /** Sem MCC ou MCC fora das faixas conhecidas. */
    OTHER("Outros");

    private final String label;

    PurchaseCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
