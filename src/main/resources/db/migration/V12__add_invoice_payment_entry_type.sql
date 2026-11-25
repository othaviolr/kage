-- Pagamento de fatura do cartão debita a conta vinculada e aparece no extrato.
ALTER TABLE account_entries DROP CONSTRAINT account_entries_type_check;
ALTER TABLE account_entries ADD CONSTRAINT account_entries_type_check
    CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'PIX_RECEIVED', 'PIX_SENT',
                    'PIX_REFUND_RECEIVED', 'PIX_REFUND_SENT', 'INVOICE_PAYMENT'));
