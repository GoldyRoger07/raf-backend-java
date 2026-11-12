-- Anti-fraude : un joueur vise ne peut pas faire de sans-faute en solo.
--
-- Si une partie solo visee est entierement juste, la correction serveur fausse au
-- hasard 1 a 3 bonnes reponses (nombre tire au hasard) avant de calculer le score : pas
-- de sans-faute, pas de prix, commission du leader comme pour une defaite. Une partie
-- avec deja une erreur n'est pas touchee. Groupe inchange.
--
-- Vise : un joueur de anti_fraud_targets (ce compte seul, pas ses comptes lies), ou tout
-- joueur si l'interrupteur app.anti_fraud.all_players est active (coupe par defaut).
-- Le staff (moderateur, admin, super admin) ne l'est jamais ; les comptes de test oui.
-- L'etat est lu au lancement et garde dans la partie (game_sessions.anti_fraud).
CREATE TABLE anti_fraud_targets (
    user_id     BIGINT        NOT NULL,
    note        VARCHAR(255)  NOT NULL,
    added_by    BIGINT,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_anti_fraud_targets_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

ALTER TABLE game_sessions
    ADD COLUMN anti_fraud BOOLEAN NOT NULL DEFAULT FALSE;

-- Vraie correction gardee pour le back-office : la reponse etait juste, l'anti-fraude
-- l'a comptee fausse (is_correct = FALSE, points_awarded = 0).
ALTER TABLE game_answers
    ADD COLUMN anti_fraud_voided BOOLEAN NOT NULL DEFAULT FALSE;
