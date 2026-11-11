-- Profils fraudeurs : regles appliquees en solo aux joueurs suspectes de fraude.
--
-- Chaque profil durcit trois valeurs d'une partie solo, toujours contre le joueur :
-- prix d'entree (+0 a +200 %), secondes par question (-0 a -80 %, au moins 1 s),
-- nombre de questions (+0 a +100 %). Le gain du sans-faute ne change pas. L'app affiche
-- les valeurs ajustees : le prix encaisse est celui que le joueur voit.
--
-- fraud_suspects : un joueur -> un profil, note obligatoire. Le profil s'etend aux
-- comptes lies par appareil (user_devices) ; plusieurs profils dans un groupe : on
-- garde, valeur par valeur, la plus severe. Un joueur liste n'a plus droit a la
-- premiere partie offerte (solo_free_offers). Seules les parties qui demarrent
-- ensuite sont touchees : la partie garde ses valeurs (game_sessions).
CREATE TABLE fraud_profiles (
    id                          BIGINT       NOT NULL AUTO_INCREMENT,
    name                        VARCHAR(80)  NOT NULL,
    price_increase_percent      INT          NOT NULL DEFAULT 0,
    duration_decrease_percent   INT          NOT NULL DEFAULT 0,
    questions_increase_percent  INT          NOT NULL DEFAULT 0,
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_fraud_profiles_name (name)
) ENGINE = InnoDB;

CREATE TABLE fraud_suspects (
    user_id     BIGINT        NOT NULL,
    profile_id  BIGINT        NOT NULL,
    note        VARCHAR(255)  NOT NULL,
    added_by    BIGINT,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (user_id),
    KEY ix_fraud_suspects_profile (profile_id),
    CONSTRAINT fk_fraud_suspects_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_fraud_suspects_profile FOREIGN KEY (profile_id)
        REFERENCES fraud_profiles (id)
) ENGINE = InnoDB;
