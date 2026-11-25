-- Outbox genérico, compartilhado por todos os contextos (antes só o PIX tinha o seu).
CREATE TABLE outbox_events (
                               id uuid NOT NULL,
                               exchange varchar(255) NOT NULL,
                               routing_key varchar(255) NOT NULL,
                               payload text NOT NULL,
                               created_at timestamp(6) NOT NULL,
                               published_at timestamp(6),
                               CONSTRAINT outbox_events_pkey PRIMARY KEY (id)
);

-- o relay só lê o que ainda não foi publicado
CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at) WHERE published_at IS NULL;

-- eventos PIX ainda não entregues não podem se perder na migração
INSERT INTO outbox_events (id, exchange, routing_key, payload, created_at, published_at)
SELECT id, exchange, routing_key, payload, created_at, published_at FROM pix_outbox;

DROP TABLE pix_outbox;
