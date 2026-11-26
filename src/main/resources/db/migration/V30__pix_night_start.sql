-- Início da janela noturna do PIX escolhido pelo cliente (Resolução BCB nº 142): das 20h (padrão) até
-- as 22h; o fim é sempre às 6h. Começar mais tarde segue a regra de aumento de limite (vale 24h depois).
ALTER TABLE accounts
    ADD COLUMN pix_night_start_hour INT NOT NULL DEFAULT 20
        CHECK (pix_night_start_hour BETWEEN 20 AND 22);

ALTER TABLE account_limit_requests DROP CONSTRAINT account_limit_requests_limit_type_check;
ALTER TABLE account_limit_requests ADD CONSTRAINT account_limit_requests_limit_type_check
    CHECK (limit_type IN ('PIX_DAILY', 'PIX_NIGHT', 'DAILY_WITHDRAWAL', 'DAILY_TRANSFER', 'MONTHLY_TRANSFER',
                          'PIX_NIGHT_START'));
