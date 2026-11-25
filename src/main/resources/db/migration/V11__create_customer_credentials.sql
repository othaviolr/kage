-- Credencial de acesso do cliente (contexto auth), separada do cadastro (contexto customer).
CREATE TABLE customer_credentials (
                                      customer_id uuid NOT NULL,
                                      password_hash varchar(100) NOT NULL,
                                      failed_attempts integer NOT NULL DEFAULT 0,
                                      locked_until timestamp(6),
                                      created_at timestamp(6) NOT NULL,
                                      updated_at timestamp(6) NOT NULL,
                                      CONSTRAINT customer_credentials_pkey PRIMARY KEY (customer_id),
                                      -- checada no commit: no signup, o cliente (JPA) só é gravado no flush do
                                      -- commit, depois do INSERT da credencial (JDBC) na mesma transação
                                      CONSTRAINT customer_credentials_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id)
                                          DEFERRABLE INITIALLY DEFERRED,
                                      CONSTRAINT customer_credentials_failed_attempts_check CHECK (failed_attempts >= 0)
);
