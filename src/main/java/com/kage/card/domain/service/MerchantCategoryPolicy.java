package com.kage.card.domain.service;

import com.kage.card.domain.enums.PurchaseCategory;
import com.kage.shared.domain.exception.ValidationException;

import java.util.Locale;

/**
 * MCC (Merchant Category Code, ISO 18245: 4 dígitos que a bandeira manda com cada autorização) →
 * categoria do Kage. As faixas seguem a tabela das bandeiras (Visa/Mastercard); o que não está aqui
 * vira {@link PurchaseCategory#OTHER}, como faz qualquer banco com um MCC que não classifica.
 */
public final class MerchantCategoryPolicy {

    private MerchantCategoryPolicy() {
    }

    /** "5411" → 5411. Nulo = compra sem MCC; formato inválido = 400. */
    public static String normalize(String mcc) {
        if (mcc == null || mcc.isBlank()) return null;
        String value = mcc.trim();
        if (!value.matches("\\d{4}")) throw new ValidationException("MCC da compra com 4 dígitos, ex.: 5411");
        return value;
    }

    public static PurchaseCategory categoryOf(String mcc) {
        String value = normalize(mcc);
        if (value == null) return PurchaseCategory.OTHER;
        int code = Integer.parseInt(value);

        // 3000–3299 companhias aéreas, 3351–3441 locadoras de carro, 3501–3999 hotéis (MCCs "de marca")
        if (code >= 3000 && code <= 3999) return PurchaseCategory.TRAVEL;
        return switch (code) {
            // mercado, hortifrúti, açougue, padaria, conveniência
            case 5411, 5422, 5441, 5451, 5462, 5499 -> PurchaseCategory.SUPERMARKET;
            // restaurante, bar, fast food (delivery de app usa estes)
            case 5811, 5812, 5813, 5814 -> PurchaseCategory.RESTAURANTS;
            // transporte público, táxi/app, ônibus, pedágio, combustível, estacionamento
            case 4111, 4112, 4121, 4131, 4784, 4789, 5541, 5542, 5983, 7523, 7538, 7542 -> PurchaseCategory.TRANSPORT;
            // companhia aérea, agência de viagem, hotel, locadora
            case 4411, 4511, 4582, 4722, 7011, 7012, 7512, 7513, 7519 -> PurchaseCategory.TRAVEL;
            // farmácia, ótica, médicos, dentistas, hospitais, laboratórios
            case 5122, 5912, 5975, 5976, 8011, 8021, 8031, 8041, 8042, 8043, 8049, 8050, 8062, 8071, 8099 ->
                    PurchaseCategory.HEALTH;
            // escolas, faculdades, cursos, livrarias
            case 5942, 8211, 8220, 8241, 8244, 8249, 8299 -> PurchaseCategory.EDUCATION;
            // streaming e TV, conteúdo digital, cinema, shows, esportes, jogos
            case 4899, 5815, 5816, 5817, 5818, 7832, 7841, 7922, 7929, 7932, 7933, 7941, 7991, 7992, 7993, 7994,
                 7996, 7997, 7998, 7999 -> PurchaseCategory.ENTERTAINMENT;
            // loja de departamento, roupas e calçados, eletrônicos, presentes, e-commerce em geral
            case 5300, 5309, 5310, 5311, 5331, 5399, 5611, 5621, 5631, 5641, 5651, 5655, 5661, 5681, 5691, 5699,
                 5732, 5733, 5734, 5735, 5940, 5941, 5943, 5944, 5945, 5947, 5948, 5964, 5999 -> PurchaseCategory.SHOPPING;
            // material de construção, móveis, eletrodomésticos, luz/água/gás
            case 4900, 5200, 5211, 5231, 5251, 5261, 5712, 5713, 5714, 5719, 5722 -> PurchaseCategory.HOME;
            // telefonia e internet, salão, lavanderia, serviços profissionais
            case 4812, 4814, 4816, 7210, 7211, 7216, 7230, 7298, 7299, 7311, 7372, 7399, 8999 ->
                    PurchaseCategory.SERVICES;
            default -> PurchaseCategory.OTHER;
        };
    }

    /** "supermarket" → SUPERMARKET; desconhecida = 400 com a lista. */
    public static PurchaseCategory parse(String raw) {
        if (raw == null || raw.isBlank()) throw new ValidationException("Informe a categoria (category)");
        try {
            return PurchaseCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Categoria inválida: use " + String.join(", ",
                    java.util.Arrays.stream(PurchaseCategory.values()).map(Enum::name).toList()));
        }
    }
}
