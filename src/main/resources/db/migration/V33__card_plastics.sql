-- Plástico do cartão físico. A 2ª via troca o plástico (número, validade, CVV) e mantém o cartão — a
-- conta de crédito com limite, faturas e rotativo. Como no virtual, o número não é guardado: ficam os
-- 4 últimos dígitos e um hash. Cartões emitidos antes desta migration não têm plástico registrado e
-- seguem comprando como antes.
CREATE TABLE card_plastics (
    id            UUID         PRIMARY KEY,
    card_id       UUID         NOT NULL REFERENCES cards (id),
    sequence      INTEGER      NOT NULL CHECK (sequence >= 1),
    last_digits   CHAR(4)      NOT NULL,
    number_hash   CHAR(64)     NOT NULL,
    expiry        VARCHAR(7)   NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'CANCELLED')),
    issue_reason  VARCHAR(20)  NOT NULL CHECK (issue_reason IN ('FIRST_ISSUE', 'LOST', 'STOLEN', 'FRAUD', 'DAMAGED')),
    issued_at     TIMESTAMP(6) NOT NULL,
    activated_at  TIMESTAMP(6),
    cancelled_at  TIMESTAMP(6),
    cancel_reason VARCHAR(20)  CHECK (cancel_reason IN ('LOST', 'STOLEN', 'FRAUD', 'DAMAGED', 'REPLACED', 'CARD_CANCELLED')),
    CONSTRAINT card_plastics_number_uq UNIQUE (number_hash),
    CONSTRAINT card_plastics_sequence_uq UNIQUE (card_id, sequence),
    CONSTRAINT card_plastics_cancel_check CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
                                                 AND (cancelled_at IS NULL) = (cancel_reason IS NULL))
);

-- no máximo um plástico valendo e uma 2ª via a caminho por cartão (o danificado vale até a nova chegar)
CREATE UNIQUE INDEX card_plastics_active_uq ON card_plastics (card_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX card_plastics_pending_uq ON card_plastics (card_id) WHERE status = 'PENDING_ACTIVATION';
