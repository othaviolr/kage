-- Categoria de cada compra: o MCC que a bandeira mandou e a categoria que ele deu, ou a que o cliente
-- escolheu (customized). Compra sem linha aqui é "Outros".
CREATE TABLE card_purchase_categories (
    card_id     UUID         NOT NULL REFERENCES cards (id),
    purchase_id UUID         NOT NULL,
    mcc         CHAR(4)      CHECK (mcc ~ '^[0-9]{4}$'),
    category    VARCHAR(20)  NOT NULL,
    customized  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (card_id, purchase_id)
);
