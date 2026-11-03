-- Suppression de compte par son titulaire : le compte est anonymise (statut
-- DELETED), pas efface, pour garder l'historique financier et les classements.
ALTER TABLE users ADD COLUMN deleted_at DATETIME(6) NULL AFTER last_login_at;
