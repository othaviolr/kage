-- Links de recuperação de senha: uso único, 30 minutos, só o SHA-256 guardado.
CREATE TABLE password_reset_tokens (
                                       token_hash varchar(64) NOT NULL,
                                       customer_id uuid NOT NULL,
                                       created_at timestamp(6) NOT NULL,
                                       expires_at timestamp(6) NOT NULL,
                                       used_at timestamp(6),
                                       CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (token_hash),
                                       CONSTRAINT password_reset_tokens_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id)
);

-- limite de pedidos por hora e invalidação dos pendentes: sempre por cliente
CREATE INDEX password_reset_tokens_customer_created_idx ON password_reset_tokens (customer_id, created_at);
