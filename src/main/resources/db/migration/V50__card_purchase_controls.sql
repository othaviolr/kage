-- Tipos de compra que o cliente liga e desliga no cartão. Sem linha = tudo ligado (o padrão).
CREATE TABLE card_purchase_controls (
    card_id       UUID         PRIMARY KEY REFERENCES cards (id),
    online        BOOLEAN      NOT NULL,
    international BOOLEAN      NOT NULL,
    contactless   BOOLEAN      NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL
);
