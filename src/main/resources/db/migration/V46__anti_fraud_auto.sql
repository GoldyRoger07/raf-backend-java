-- Anti-fraude : ajout automatique a la liste apres N sans-faute solo payants.
--
-- Reglage sur l'ecran Anti-fraude : app.anti_fraud.auto_enabled (coupe par defaut) et
-- app.anti_fraud.auto_threshold (N, 3 par defaut). Comptent les parties solo sans-faute
-- a prix d'entree non nul (ni offerte, ni quiz gratuit, ni essai), de ce compte seul,
-- depuis toujours. Le staff et les comptes de test ne sont jamais ajoutes. Le Nieme
-- sans-faute est paye ; l'anti-fraude touche les parties lancees ensuite.
--
-- Un joueur retire de la liste repart de zero : seuls ses sans-faute faits apres
-- counted_since comptent pour un nouvel ajout automatique.
ALTER TABLE anti_fraud_targets
    ADD COLUMN auto BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE anti_fraud_auto_resets (
    user_id        BIGINT       NOT NULL,
    counted_since  DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_anti_fraud_auto_resets_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;
