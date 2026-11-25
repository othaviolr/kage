-- PIX recorrente: semanal ou mensal, por um número fixo de vezes. Só a PRÓXIMA ocorrência existe
-- como PIX agendado (pix_transactions); quando ela é executada, recusada ou pulada, a seguinte é criada.
CREATE TABLE pix_recurrences (
    id                  UUID PRIMARY KEY,
    source_account_id   UUID           NOT NULL REFERENCES accounts (id),
    target_pix_key      VARCHAR(255)   NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    description         VARCHAR(255),
    frequency           VARCHAR(10)    NOT NULL CHECK (frequency IN ('WEEKLY', 'MONTHLY')),
    start_date          DATE           NOT NULL,
    total_occurrences   INT            NOT NULL CHECK (total_occurrences BETWEEN 2 AND 60),
    occurrences_created INT            NOT NULL CHECK (occurrences_created BETWEEN 0 AND total_occurrences),
    status              VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'FINISHED', 'CANCELLED')),
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP      NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX pix_recurrences_account_idx ON pix_recurrences (source_account_id, status);

ALTER TABLE pix_transactions
    ADD COLUMN recurrence_id     UUID REFERENCES pix_recurrences (id),
    ADD COLUMN occurrence_number INT,
    ADD CONSTRAINT pix_transactions_occurrence_chk
        CHECK ((recurrence_id IS NULL) = (occurrence_number IS NULL));

-- a mesma ocorrência nunca é criada duas vezes (duas instâncias do job, reentrega)
CREATE UNIQUE INDEX pix_transactions_recurrence_occurrence_uq
    ON pix_transactions (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL;
