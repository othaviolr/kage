-- Troca do dia de vencimento da fatura: histórico de cada troca (uma a cada 180 dias).
CREATE TABLE card_due_day_changes (
    id                   UUID         PRIMARY KEY,
    card_id              UUID         NOT NULL REFERENCES cards (id),
    previous_closing_day SMALLINT     NOT NULL CHECK (previous_closing_day BETWEEN 1 AND 28),
    previous_due_day     SMALLINT     NOT NULL CHECK (previous_due_day BETWEEN 1 AND 28),
    closing_day          SMALLINT     NOT NULL CHECK (closing_day BETWEEN 1 AND 28),
    due_day              SMALLINT     NOT NULL CHECK (due_day BETWEEN 1 AND 28),
    changed_at           TIMESTAMP(6) NOT NULL
);

CREATE INDEX card_due_day_changes_card_idx ON card_due_day_changes (card_id, changed_at DESC);
