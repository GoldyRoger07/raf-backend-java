-- =============================================================================
-- RAF - Quiz gratuits et lien Instagram
--
-- Un quiz (de groupe ou solo) peut etre gratuit : son prix vaut alors 0 credit.
-- Prendre un ticket ou lancer une partie ne debite rien, et aucun mouvement de
-- portefeuille n'est ecrit (un mouvement de 0 n'existe pas). Les recompenses,
-- elles, restent de vrais gains en HTG.
--
-- Le lien vers le compte Instagram de Raiki Services s'affiche dans l'app et sur
-- le site quand l'administrateur l'active. Ces deux parametres ne sont pas des
-- entiers : ils ont leur propre ecran et n'apparaissent pas dans Parametres.
-- =============================================================================

ALTER TABLE quizzes DROP CHECK ck_quizzes_price;
ALTER TABLE quizzes ADD CONSTRAINT ck_quizzes_price CHECK (ticket_price_credits >= 0);

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('social.instagram.enabled', 'false',
     'Affiche le lien vers le compte Instagram dans l app et sur le site', NOW(6), NOW(6)),
    ('social.instagram.url', NULL,
     'Adresse du compte Instagram de Raiki Services', NOW(6), NOW(6));
