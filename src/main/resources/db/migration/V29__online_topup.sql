-- Recharge en ligne par MonCash ou NatCash, via la passerelle Tranzak.
--
-- Le joueur paie les frais de la passerelle : il demande un montant (amount_htg,
-- converti en credits comme toute recharge) et paie charged_htg = amount_htg +
-- fee_htg. Tranzak preleve son pourcentage sur charged_htg, RAF recoit au moins
-- amount_htg.
--
-- provider_ref garde l'identifiant de transaction Tranzak : la contrainte unique
-- (provider, provider_ref) existante empeche deux recharges sur un meme paiement.
ALTER TABLE topup_requests
    ADD COLUMN fee_htg     BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN charged_htg BIGINT,
    ADD COLUMN payment_url VARCHAR(500),
    ADD COLUMN checked_at  DATETIME(6),
    ADD KEY ix_topup_pending_online (status, provider, created_at);

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('topup.online_fee_bps', '450',
     'Frais de la passerelle de paiement factures au joueur sur une recharge en ligne, en points de base (450 = 4,5 %)',
     NOW(6), NOW(6)),
    ('topup.online_min_htg', '50', 'Montant minimum d une recharge en ligne (HTG, hors frais)', NOW(6), NOW(6)),
    ('topup.online_max_htg', '25000', 'Montant maximum d une recharge en ligne (HTG, hors frais)', NOW(6), NOW(6));
