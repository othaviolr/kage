-- Limite de PIX passa a somar pelo momento em que o dinheiro saiu (processed_at), não pela criação:
-- um agendamento feito hoje consome o limite do dia da execução.
CREATE INDEX pix_transactions_source_processed_idx ON pix_transactions (source_account_id, processed_at);

-- o job diário procura agendamentos pendentes até a data de hoje
CREATE INDEX pix_transactions_scheduled_pending_idx ON pix_transactions (scheduled_date)
    WHERE transaction_type = 'SCHEDULED' AND status = 'PENDING';
