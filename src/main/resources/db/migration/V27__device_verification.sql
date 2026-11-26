-- Verificação em duas etapas no login de dispositivo novo. O app manda um identificador do aparelho
-- (X-Device-Id, gerado na instalação); só o SHA-256 dele é guardado.
CREATE TABLE trusted_devices (
    id           UUID         PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id) DEFERRABLE INITIALLY DEFERRED,
    device_hash  VARCHAR(64)  NOT NULL,
    user_agent   VARCHAR(255),
    trusted_at   TIMESTAMP(6) NOT NULL,
    last_seen_at TIMESTAMP(6) NOT NULL,
    revoked_at   TIMESTAMP(6)
);

-- um aparelho confiável por cliente (os esquecidos ficam de histórico)
CREATE UNIQUE INDEX trusted_devices_active_uq ON trusted_devices (customer_id, device_hash) WHERE revoked_at IS NULL;

-- Desafio do login: código de 6 dígitos de uso único, 5 minutos, até 5 tentativas; só o hash guardado.
CREATE TABLE login_challenges (
    id          UUID         PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    code_hash   VARCHAR(64)  NOT NULL,
    device_hash VARCHAR(64),
    user_agent  VARCHAR(255),
    created_at  TIMESTAMP(6) NOT NULL,
    expires_at  TIMESTAMP(6) NOT NULL,
    attempts    INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    used_at     TIMESTAMP(6)
);

-- limite de códigos por hora: sempre por cliente
CREATE INDEX login_challenges_customer_created_idx ON login_challenges (customer_id, created_at);
