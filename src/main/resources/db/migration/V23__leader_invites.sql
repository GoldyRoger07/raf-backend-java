-- Invitations : un joueur partage un lien d'inscription portant son identifiant.
-- Si c'est un leader, le nouvel inscrit lui est rattache et recoit, une fois son
-- compte verifie (email puis telephone), un bonus de credits offert par la
-- plateforme, dont le montant est fixe par leader au back-office.

-- Bonus offert a chaque joueur inscrit avec le lien de ce leader. 0 = aucun bonus.
ALTER TABLE leader_profiles
    ADD COLUMN signup_bonus_credits INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_leader_signup_bonus CHECK (signup_bonus_credits >= 0);

-- Rattachement venu d'un lien d'invitation (et non ajoute par un administrateur),
-- avec le bonus promis, fige au moment de l'inscription. Il est verse plus tard.
ALTER TABLE leader_players
    ADD COLUMN via_invite           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN signup_bonus_credits INT     NOT NULL DEFAULT 0;

-- Bonus verses : une ligne par joueur au plus. Table a part pour que l'historique
-- survive au detachement du joueur ou au retrait du role leader.
CREATE TABLE leader_invite_bonuses (
    player_id      BIGINT      NOT NULL,
    leader_id      BIGINT      NOT NULL,
    credits        INT         NOT NULL,
    transaction_id BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (player_id),
    KEY ix_leader_invite_bonuses_leader (leader_id, created_at),
    UNIQUE KEY uk_leader_invite_bonuses_transaction (transaction_id),
    CONSTRAINT fk_leader_invite_bonuses_player FOREIGN KEY (player_id) REFERENCES users (id),
    CONSTRAINT fk_leader_invite_bonuses_leader FOREIGN KEY (leader_id) REFERENCES users (id),
    CONSTRAINT fk_leader_invite_bonuses_transaction FOREIGN KEY (transaction_id) REFERENCES wallet_transactions (id),
    CONSTRAINT ck_leader_invite_bonuses_credits CHECK (credits > 0)
) ENGINE = InnoDB;
