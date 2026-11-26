-- E-mails enviados ao cliente (hoje: a fatura no fechamento, com o PDF anexo). Auditoria da
-- comunicação e idempotência pelo evento de origem. O anexo não é guardado — sai de novo da fatura —,
-- só o nome, o tamanho e o SHA-256 (prova de qual documento foi).
CREATE TABLE email_log (
    id                UUID         PRIMARY KEY,
    customer_id       UUID         NOT NULL REFERENCES customers (id),
    kind              VARCHAR(30)  NOT NULL,
    to_address        VARCHAR(255) NOT NULL,
    subject           VARCHAR(255) NOT NULL,
    attachment_name   VARCHAR(120),
    attachment_size   INTEGER,
    attachment_sha256 CHAR(64),
    reference_id      UUID,
    source_event_id   VARCHAR(64)  NOT NULL,
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    failure_reason    VARCHAR(255),
    created_at        TIMESTAMP(6) NOT NULL,
    sent_at           TIMESTAMP(6),
    CONSTRAINT email_log_source_event_uq UNIQUE (source_event_id),
    CONSTRAINT email_log_sent_check CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

CREATE INDEX email_log_customer_idx ON email_log (customer_id, created_at DESC);
