-- Cobrar com PIX copia e cola: o cliente gera um código para uma chave sua, com valor (ou em aberto) e
-- mensagem. O txid identifica a cobrança quando alguém paga pelo PIX da conta Kage.
CREATE TABLE pix_charges (
    id           UUID           PRIMARY KEY,
    account_id   UUID           NOT NULL REFERENCES accounts (id),
    pix_key      VARCHAR(100)   NOT NULL,
    txid         VARCHAR(25)    NOT NULL UNIQUE,
    amount       NUMERIC(19, 2) CHECK (amount > 0),
    message      VARCHAR(72),
    single_use   BOOLEAN        NOT NULL,
    code         TEXT           NOT NULL,
    status       VARCHAR(10)    NOT NULL CHECK (status IN ('OPEN', 'CANCELLED')),
    created_at   TIMESTAMP(6)   NOT NULL,
    cancelled_at TIMESTAMP(6)
);

CREATE INDEX pix_charges_account_idx ON pix_charges (account_id, created_at DESC);

-- Quem pagou cada cobrança: um PIX (transação) por linha; o status vem da própria transação.
CREATE TABLE pix_charge_payments (
    transaction_id   UUID           PRIMARY KEY,
    charge_id        UUID           NOT NULL REFERENCES pix_charges (id),
    payer_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    paid_at          TIMESTAMP(6)   NOT NULL
);

CREATE INDEX pix_charge_payments_charge_idx ON pix_charge_payments (charge_id, paid_at);
