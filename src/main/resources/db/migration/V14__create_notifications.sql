-- Caixa de entrada de avisos do cliente (contexto notification).
CREATE TABLE notifications (
                               id uuid NOT NULL,
                               customer_id uuid NOT NULL,
                               type varchar(40) NOT NULL,
                               title varchar(120) NOT NULL,
                               message varchar(500),
                               reference_id uuid,
                               -- id da mensagem (messageId do outbox) que gerou o aviso: reentrega não duplica
                               source_event_id varchar(64) NOT NULL,
                               created_at timestamp(6) NOT NULL,
                               read_at timestamp(6),
                               CONSTRAINT notifications_pkey PRIMARY KEY (id),
                               CONSTRAINT notifications_source_event_key UNIQUE (source_event_id),
                               CONSTRAINT notifications_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id)
);

CREATE INDEX notifications_customer_created_idx ON notifications (customer_id, created_at DESC);
CREATE INDEX notifications_customer_unread_idx ON notifications (customer_id) WHERE read_at IS NULL;
