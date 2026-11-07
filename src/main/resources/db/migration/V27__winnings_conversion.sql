-- Recharge avec les gains : le joueur convertit une partie de son solde de gains
-- (HTG, retirable) en credits (non retirables). C'est une recharge comme les
-- autres (topup_requests, fournisseur WINNINGS), payee par un debit de son solde
-- de gains au lieu d'un paiement MonCash, NatCash ou en especes.
--
-- Seules des gourdes formant des credits entiers sont debitees : le reste demeure
-- dans le solde de gains, la retenue de recharge n'intervient pas.
ALTER TABLE topup_requests
    ADD COLUMN debit_transaction_id BIGINT,
    ADD UNIQUE KEY uk_topup_requests_debit (debit_transaction_id),
    ADD CONSTRAINT fk_topup_requests_debit FOREIGN KEY (debit_transaction_id) REFERENCES wallet_transactions (id);
