-- PIX Automático: quem recebe pede a autorização, o pagador autoriza, e cada ciclo tem uma cobrança,
-- que vira um PIX agendado comum (pix_transactions) no vencimento.
CREATE TABLE pix_automatic_authorizations (
    id                  UUID PRIMARY KEY,
    payer_account_id    UUID           NOT NULL REFERENCES accounts (id),
    receiver_account_id UUID           NOT NULL REFERENCES accounts (id),
    receiver_pix_key    VARCHAR(255)   NOT NULL,
    receiver_name       VARCHAR(255)   NOT NULL,
    description         VARCHAR(255)   NOT NULL,
    contract_reference  VARCHAR(100),
    frequency           VARCHAR(12)    NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    fixed_amount        NUMERIC(19, 2) CHECK (fixed_amount > 0),
    max_amount          NUMERIC(19, 2) CHECK (max_amount > 0),
    start_date          DATE           NOT NULL,
    end_date            DATE,
    status              VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'CANCELLED')),
    cancelled_by        VARCHAR(10)    CHECK (cancelled_by IN ('PAYER', 'RECEIVER', 'ADMIN')),
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP      NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    -- valor máximo é do pagador, e só no valor variável
    CONSTRAINT pix_automatic_amount_chk CHECK (fixed_amount IS NULL OR max_amount IS NULL),
    CONSTRAINT pix_automatic_period_chk CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT pix_automatic_parties_chk CHECK (payer_account_id <> receiver_account_id)
);

CREATE INDEX pix_automatic_payer_idx ON pix_automatic_authorizations (payer_account_id);
CREATE INDEX pix_automatic_receiver_idx ON pix_automatic_authorizations (receiver_account_id);

CREATE TABLE pix_automatic_charges (
    id                 UUID PRIMARY KEY,
    authorization_id   UUID           NOT NULL REFERENCES pix_automatic_authorizations (id),
    cycle              INT            NOT NULL CHECK (cycle >= 0),
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    due_date           DATE           NOT NULL,
    -- o PIX agendado é gravado pelo JPA no flush, depois deste INSERT: a FK é conferida no commit
    pix_transaction_id UUID           NOT NULL UNIQUE
        REFERENCES pix_transactions (transaction_id) DEFERRABLE INITIALLY DEFERRED,
    created_at         TIMESTAMP      NOT NULL,
    -- uma cobrança por ciclo
    CONSTRAINT pix_automatic_charges_cycle_uq UNIQUE (authorization_id, cycle)
);
