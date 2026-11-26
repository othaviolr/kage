-- Preferências de push por categoria. Sem linha, a categoria está ligada. Desligar tira só o push: o
-- aviso continua na caixa de entrada. Categorias obrigatórias (segurança, fatura, serviços do cartão)
-- não desligam — a regra fica na aplicação, que também manda os avisos críticos mesmo desligados.
CREATE TABLE notification_preferences (
    customer_id UUID         NOT NULL REFERENCES customers (id),
    category    VARCHAR(30)  NOT NULL CHECK (category IN
                    ('SECURITY', 'INVOICE', 'CARD_SERVICES', 'CARD_PURCHASES', 'PIX', 'ACCOUNT', 'BOLETO', 'SAVINGS')),
    enabled     BOOLEAN      NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (customer_id, category)
);

-- o aviso foi por push (ou ficou só no app porque o cliente desligou a categoria)
ALTER TABLE notifications ADD COLUMN pushed BOOLEAN NOT NULL DEFAULT TRUE;
