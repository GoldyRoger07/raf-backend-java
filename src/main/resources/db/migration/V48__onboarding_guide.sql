-- Guide de demarrage de l'app joueur (driver.js), propose une fois apres l'inscription.
--
-- Vrai pour les comptes crees a partir de cette version (AuthService#register) ; les
-- comptes existants restent a FALSE : on ne leur propose pas le guide automatiquement,
-- ils le trouvent dans Profil > Aide et support. Remis a FALSE quand le joueur a fait
-- ou refuse la visite (POST /api/me/onboarding/done) : memorise sur le compte, pas
-- sur l'appareil.
ALTER TABLE users
    ADD COLUMN onboarding_pending BOOLEAN NOT NULL DEFAULT FALSE;
