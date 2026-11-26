-- Fatura digital opcional: o cliente pode não querer a fatura por e-mail (ela continua no app, com o
-- aviso e o PDF para baixar). Sem linha = recebe.
CREATE TABLE invoice_email_settings (
    customer_id UUID         PRIMARY KEY REFERENCES customers (id),
    enabled     BOOLEAN      NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);

-- e-mail não mandado por opção do cliente: fica registrado que a fatura fechou e por que não foi
ALTER TABLE email_log DROP CONSTRAINT email_log_status_check;
ALTER TABLE email_log ADD CONSTRAINT email_log_status_check
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED'));
