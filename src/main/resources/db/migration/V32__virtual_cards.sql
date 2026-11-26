-- Cartão virtual: número próprio para compras online, ligado ao cartão físico (mesmo limite, mesma
-- fatura). O número completo NÃO é guardado — sai de um HMAC do id com o segredo do banco; aqui ficam
-- os 4 últimos dígitos (para mostrar) e um hash (para achar o cartão quando a compra chega com o número).
CREATE TABLE virtual_cards (
    id           UUID         PRIMARY KEY,
    card_id      UUID         NOT NULL REFERENCES cards (id),
    nickname     VARCHAR(30)  NOT NULL,
    kind         VARCHAR(10)  NOT NULL CHECK (kind IN ('PERMANENT', 'TEMPORARY')),
    last_digits  CHAR(4)      NOT NULL,
    number_hash  CHAR(64)     NOT NULL,
    expiry       VARCHAR(7)   NOT NULL,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED')),
    created_at   TIMESTAMP(6) NOT NULL,
    expires_at   TIMESTAMP(6),
    deleted_at   TIMESTAMP(6),
    CONSTRAINT virtual_cards_number_uq UNIQUE (number_hash),
    CONSTRAINT virtual_cards_temporary_check CHECK ((kind = 'TEMPORARY') = (expires_at IS NOT NULL)),
    CONSTRAINT virtual_cards_deleted_check CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

CREATE INDEX virtual_cards_card_idx ON virtual_cards (card_id, created_at DESC);

-- de qual cartão virtual veio a compra (nulo = cartão físico)
ALTER TABLE invoice_items ADD COLUMN virtual_card_id UUID REFERENCES virtual_cards (id);

-- "não reconheço" numa compra do virtual apaga só ele, sem bloquear o físico
ALTER TABLE card_disputes ADD COLUMN virtual_card_id UUID REFERENCES virtual_cards (id);
ALTER TABLE card_disputes ADD COLUMN virtual_card_deleted BOOLEAN NOT NULL DEFAULT FALSE;
