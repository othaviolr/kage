-- Cashback de 1% sobre as compras da fatura paga inteira até o vencimento: entra como crédito na fatura
-- seguinte; se a compra for contestada com sucesso depois, o cashback dela volta como cobrança.
ALTER TABLE invoice_items DROP CONSTRAINT invoice_items_kind_check;
ALTER TABLE invoice_items ADD CONSTRAINT invoice_items_kind_check
    CHECK (kind IN ('PURCHASE', 'PREVIOUS_BALANCE', 'REVOLVING_INTEREST', 'LATE_FINE', 'LATE_INTEREST',
                    'INVOICE_INSTALLMENT', 'ANTICIPATION', 'DISPUTE_CREDIT', 'DISPUTE_REVERSAL', 'CREDIT_BALANCE',
                    'DISPUTE_INTEREST_REFUND', 'IOF', 'CASHBACK', 'CASHBACK_REVERSAL'));
