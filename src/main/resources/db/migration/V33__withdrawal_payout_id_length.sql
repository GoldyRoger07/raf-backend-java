-- Tranzak ne documente pas le format de ses identifiants de versement. Un
-- identifiant de plus de 60 caracteres faisait echouer l'enregistrement alors que
-- l'argent etait deja parti : le retrait restait bloque en SENDING. payout_id et
-- payout_reference (qui le recoit une fois le versement confirme) passent a 191,
-- la longueur maximale d'une cle unique en utf8mb4 sur les anciens formats InnoDB.
ALTER TABLE withdrawal_requests
    MODIFY COLUMN payout_id        VARCHAR(191),
    MODIFY COLUMN payout_reference VARCHAR(191);
