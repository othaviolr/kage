-- Validade opcional da cobrança PIX: depois dela, o Kage não paga mais o código (nulo = vale até ser
-- paga ou cancelada). O BR Code estático não tem campo de validade: quem confere é o Kage.
ALTER TABLE pix_charges ADD COLUMN expires_at TIMESTAMP(6);
