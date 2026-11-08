-- Retraits automatiques MonCash / NatCash par les payouts Tranzak.
--
-- A l'approbation, RAF envoie le montant net au numero du joueur. Le retrait
-- passe en SENDING pendant l'envoi, puis PAID (versement confirme) ou revient en
-- APPROVED avec payout_error (echec : l'admin relance ou rejette).
--
-- Chaque essai porte sa propre reference chez Tranzak (RAF-WD-{id}-{essai}) :
-- une reference ne sert qu'une fois, meme apres un echec.
--
-- automatic_payout : frais calcules au pourcentage de la passerelle
-- (withdrawal.payout_fee_bps) au lieu des frais fixes withdrawal.fee_htg.
ALTER TABLE withdrawal_requests
    ADD COLUMN automatic_payout  BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN payout_attempts   INT          NOT NULL DEFAULT 0,
    ADD COLUMN payout_id         VARCHAR(60),
    ADD COLUMN payout_error      VARCHAR(255),
    ADD COLUMN payout_sent_at    DATETIME(6),
    ADD COLUMN payout_checked_at DATETIME(6),
    ADD UNIQUE KEY uk_withdrawal_payout_id (payout_id),
    ADD KEY ix_withdrawal_status_checked (status, payout_checked_at);

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('withdrawal.payout_fee_bps', '350',
     'Frais de la passerelle retenus au joueur sur un retrait automatique MonCash / NatCash, en points de base (350 = 3,5 %)',
     NOW(6), NOW(6));
