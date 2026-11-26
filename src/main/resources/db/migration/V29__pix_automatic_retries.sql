-- Novas tentativas do PIX Automático: se a cobrança falha por falta de saldo (ou de limite), quem
-- recebe pode agendar novas tentativas, se a autorização previr. Regra do arranjo: no máximo 3, uma por
-- dia, até 7 dias depois do vencimento. O pagador vê a política quando autoriza.
ALTER TABLE pix_automatic_authorizations
    ADD COLUMN retry_policy VARCHAR(20) NOT NULL DEFAULT 'NONE'
        CHECK (retry_policy IN ('NONE', 'THREE_IN_SEVEN_DAYS'));

-- cada tentativa é um PIX agendado; a cobrança passa a ter várias
CREATE TABLE pix_automatic_charge_attempts (
    id                 UUID         PRIMARY KEY,
    charge_id          UUID         NOT NULL REFERENCES pix_automatic_charges (id),
    attempt_number     INT          NOT NULL CHECK (attempt_number BETWEEN 1 AND 4), -- a original + 3
    scheduled_date     DATE         NOT NULL,
    -- gravado pelo JPA no flush, depois deste INSERT: a FK é conferida no commit
    pix_transaction_id UUID         NOT NULL UNIQUE
        REFERENCES pix_transactions (transaction_id) DEFERRABLE INITIALLY DEFERRED,
    created_at         TIMESTAMP    NOT NULL,
    CONSTRAINT pix_automatic_attempts_number_uq UNIQUE (charge_id, attempt_number),
    -- uma por dia
    CONSTRAINT pix_automatic_attempts_date_uq UNIQUE (charge_id, scheduled_date)
);

-- o que existia vira a 1ª tentativa
INSERT INTO pix_automatic_charge_attempts (id, charge_id, attempt_number, scheduled_date, pix_transaction_id, created_at)
SELECT gen_random_uuid(), id, 1, due_date, pix_transaction_id, created_at FROM pix_automatic_charges;

ALTER TABLE pix_automatic_charges DROP COLUMN pix_transaction_id;
