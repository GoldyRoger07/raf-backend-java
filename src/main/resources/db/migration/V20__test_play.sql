-- Jeu des comptes de test : tickets et parties marques a leur creation (instantane :
-- demarquer un compte plus tard ne change pas son passe). Ils sont gratuits, ne
-- rapportent rien, et sur un vrai quiz ne comptent ni pour le seuil, ni pour la
-- capacite, ni pour la cloture, ni pour le classement.
ALTER TABLE tickets ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE game_sessions ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
