-- Leaders : un joueur a qui l'on confie d'autres joueurs, et qui touche une
-- commission (en HTG, sur ses gains) sur les parties perdues de ces joueurs.

INSERT INTO roles (name, description) VALUES
    ('LEADER', 'Leader : touche une commission sur les parties perdues de ses joueurs');

-- Reglage du leader. Absent = commission a 0 %.
CREATE TABLE leader_profiles (
    user_id            BIGINT       NOT NULL,
    commission_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_leader_profiles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_leader_commission_range CHECK (commission_percent >= 0 AND commission_percent <= 100)
) ENGINE = InnoDB;

-- Rattachement d'un joueur a son leader : un joueur n'a qu'un leader (cle primaire).
CREATE TABLE leader_players (
    player_id   BIGINT      NOT NULL,
    leader_id   BIGINT      NOT NULL,
    added_at    DATETIME(6) NOT NULL,
    added_by_id BIGINT,
    PRIMARY KEY (player_id),
    KEY ix_leader_players_leader (leader_id, added_at),
    CONSTRAINT fk_leader_players_player FOREIGN KEY (player_id) REFERENCES users (id),
    CONSTRAINT fk_leader_players_leader FOREIGN KEY (leader_id) REFERENCES users (id),
    CONSTRAINT ck_leader_players_not_self CHECK (player_id <> leader_id)
) ENGINE = InnoDB;

-- Detail des commissions versees, une ligne par joueur et par quiz (groupe) ou
-- par partie (solo). Plusieurs lignes d'un meme quiz partagent le mouvement du
-- leader (un seul credit par leader et par quiz). Le pourcentage et le taux sont
-- figes au moment du versement.
CREATE TABLE leader_commissions (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    leader_id          BIGINT       NOT NULL,
    player_id          BIGINT       NOT NULL,
    source             VARCHAR(10)  NOT NULL,
    -- quiz:{quiz}:player:{joueur} ou solo:{partie} : une commission ne part qu une fois.
    source_key         VARCHAR(80)  NOT NULL,
    quiz_id            BIGINT       NOT NULL,
    session_id         BIGINT,
    base_credits       BIGINT       NOT NULL,
    htg_rate           BIGINT       NOT NULL,
    commission_percent DECIMAL(5,2) NOT NULL,
    amount_htg         BIGINT       NOT NULL,
    transaction_id     BIGINT       NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leader_commissions_source (source_key),
    KEY ix_leader_commissions_leader (leader_id, created_at),
    KEY ix_leader_commissions_player (player_id),
    KEY ix_leader_commissions_transaction (transaction_id),
    CONSTRAINT fk_leader_commissions_leader FOREIGN KEY (leader_id) REFERENCES users (id),
    CONSTRAINT fk_leader_commissions_player FOREIGN KEY (player_id) REFERENCES users (id),
    CONSTRAINT fk_leader_commissions_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_leader_commissions_transaction FOREIGN KEY (transaction_id) REFERENCES wallet_transactions (id),
    CONSTRAINT ck_leader_commissions_amount CHECK (amount_htg > 0)
) ENGINE = InnoDB;
