-- Número de conta vem de uma sequence: sem sorteio, sem corrida entre dois cadastros simultâneos.
-- Começa em 10000000 (8 dígitos) para nunca colidir com as contas antigas, de 5 dígitos aleatórios.
ALTER TABLE accounts ALTER COLUMN account_number TYPE varchar(8);

CREATE SEQUENCE account_number_seq START WITH 10000000 INCREMENT BY 1 MAXVALUE 99999999 NO CYCLE;
