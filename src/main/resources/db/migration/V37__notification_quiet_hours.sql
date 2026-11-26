-- Horário silencioso do cliente: avisos não críticos nessa janela ficam só na caixa de entrada. Sem
-- linha, vale o padrão (desligado, 22:00–07:00). A janela pode atravessar a meia-noite.
CREATE TABLE notification_quiet_hours (
    customer_id UUID         PRIMARY KEY REFERENCES customers (id),
    enabled     BOOLEAN      NOT NULL,
    start_time  TIME         NOT NULL,
    end_time    TIME         NOT NULL CHECK (end_time <> start_time),
    updated_at  TIMESTAMP(6) NOT NULL
);

-- por que o aviso ficou sem push (nulo = foi por push)
ALTER TABLE notifications ADD COLUMN push_silenced_reason VARCHAR(20)
    CHECK (push_silenced_reason IN ('CATEGORY_DISABLED', 'QUIET_HOURS'));
-- até aqui, o único motivo para ficar sem push era a categoria desligada
UPDATE notifications SET push_silenced_reason = 'CATEGORY_DISABLED' WHERE NOT pushed;
ALTER TABLE notifications ADD CONSTRAINT notifications_silenced_check
    CHECK (pushed = (push_silenced_reason IS NULL));
