-- Avisos do empréstimo: categoria própria nas preferências de push, e as marcas de "já avisado" nas
-- parcelas — o lembrete de vencimento e o aviso de débito que não passou saem uma vez só por parcela,
-- embora o débito seja tentado todo dia.
ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_category_check;
ALTER TABLE notification_preferences ADD CONSTRAINT notification_preferences_category_check
    CHECK (category IN ('SECURITY', 'INVOICE', 'CARD_SERVICES', 'CARD_PURCHASES', 'PIX', 'ACCOUNT', 'BOLETO', 'SAVINGS',
                        'LOANS'));

ALTER TABLE loan_installments ADD COLUMN reminded_at TIMESTAMP(6);
ALTER TABLE loan_installments ADD COLUMN failure_notified_at TIMESTAMP(6);
