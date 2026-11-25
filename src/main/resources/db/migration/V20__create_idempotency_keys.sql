-- Idempotency-Key das rotas que movem dinheiro: o reenvio de uma requisição (retry de rede, duplo
-- clique) devolve a resposta guardada em vez de executar de novo.
CREATE TABLE idempotency_keys (
                                  owner varchar(64) NOT NULL,           -- quem chamou: a mesma chave de dois clientes não colide
                                  idempotency_key varchar(64) NOT NULL,
                                  request_method varchar(10) NOT NULL,
                                  request_path varchar(255) NOT NULL,
                                  request_hash varchar(64) NOT NULL,    -- SHA-256 do corpo: mesma chave + outro corpo = erro do cliente
                                  status varchar(16) NOT NULL,
                                  response_status integer,
                                  response_body text,
                                  created_at timestamp(6) NOT NULL,
                                  expires_at timestamp(6) NOT NULL,
                                  CONSTRAINT idempotency_keys_pkey PRIMARY KEY (owner, idempotency_key),
                                  CONSTRAINT idempotency_keys_status_check CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);
