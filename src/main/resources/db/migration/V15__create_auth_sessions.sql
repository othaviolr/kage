-- Sessões autenticadas: o access token carrega o id da sessão, e o filtro de segurança confere a
-- cada requisição se ela está ativa (logout e bloqueio valem na hora).
CREATE TABLE auth_sessions (
                               id uuid NOT NULL,
                               subject varchar(64) NOT NULL,
                               customer_id uuid,
                               role varchar(16) NOT NULL,
                               user_agent varchar(255),
                               created_at timestamp(6) NOT NULL,
                               last_refreshed_at timestamp(6) NOT NULL,
                               expires_at timestamp(6) NOT NULL,
                               revoked_at timestamp(6),
                               revoke_reason varchar(40),
                               CONSTRAINT auth_sessions_pkey PRIMARY KEY (id),
                               CONSTRAINT auth_sessions_role_check CHECK (role IN ('CUSTOMER', 'ADMIN')),
                               CONSTRAINT auth_sessions_customer_check CHECK ((role = 'CUSTOMER') = (customer_id IS NOT NULL)),
                               -- deferrable pelo mesmo motivo de customer_credentials (cliente JPA ainda sem flush)
                               CONSTRAINT auth_sessions_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id)
                                   DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX auth_sessions_customer_active_idx ON auth_sessions (customer_id) WHERE revoked_at IS NULL;

-- Refresh tokens: só o SHA-256. Um por rotação; o usado fica guardado para detectar reuso.
CREATE TABLE auth_refresh_tokens (
                                     token_hash varchar(64) NOT NULL,
                                     session_id uuid NOT NULL,
                                     issued_at timestamp(6) NOT NULL,
                                     expires_at timestamp(6) NOT NULL,
                                     used_at timestamp(6),
                                     CONSTRAINT auth_refresh_tokens_pkey PRIMARY KEY (token_hash),
                                     CONSTRAINT auth_refresh_tokens_session_fkey FOREIGN KEY (session_id) REFERENCES auth_sessions (id)
);

CREATE INDEX auth_refresh_tokens_session_idx ON auth_refresh_tokens (session_id);
