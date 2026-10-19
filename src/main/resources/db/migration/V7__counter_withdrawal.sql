-- =============================================================================
-- RAF - Lot 7 : retrait au guichet
--
-- L'administrateur saisit le retrait pour un joueur present au guichet. Le
-- joueur l'autorise en donnant le code OTP recu sur son telephone verifie : sans
-- ce code, un compte administrateur ne peut pas vider les gains d'un joueur.
--
-- Un retrait au guichet n'a pas de numero de reception (remise en especes), et
-- porte l'administrateur qui l'a saisi.
-- =============================================================================

ALTER TABLE withdrawal_requests
    MODIFY COLUMN destination_number VARCHAR(20) NULL,
    ADD COLUMN initiated_by_admin_id BIGINT NULL AFTER otp_verified_at,
    ADD COLUMN note VARCHAR(255) NULL AFTER payout_reference;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('withdrawal.counter_fee_htg', '25',
     'Frais fixes preleves sur un retrait au guichet, en gourdes', NOW(6), NOW(6));
