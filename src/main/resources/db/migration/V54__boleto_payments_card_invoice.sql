-- Boleto da fatura do cartão pago pelo "pagar boleto" da conta Kage: o dinheiro vai direto para a fatura,
-- sem boleto de cliente por trás (boleto_id nulo).
ALTER TABLE boleto_payments DROP CONSTRAINT boleto_payments_kind_check;
ALTER TABLE boleto_payments ADD CONSTRAINT boleto_payments_kind_check
    CHECK (kind IN ('INTERNAL', 'EXTERNAL', 'CARD_INVOICE'));
