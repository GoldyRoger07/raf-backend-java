-- Recharge d'un joueur par son leader : le leader cede une partie de son solde de
-- credits a l'un de ses joueurs rattaches. Une ligne par recharge, qui reference
-- les deux mouvements (debit du leader, credit du joueur) pour le rapprochement.
CREATE TABLE leader_credit_transfers (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    leader_id             BIGINT      NOT NULL,
    player_id             BIGINT      NOT NULL,
    credits               BIGINT      NOT NULL,
    debit_transaction_id  BIGINT      NOT NULL,
    credit_transaction_id BIGINT      NOT NULL,
    -- Cle fournie par l'app : un double envoi de la meme recharge n'est applique qu'une fois.
    request_key           VARCHAR(64) NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leader_credit_transfers_request (leader_id, request_key),
    UNIQUE KEY uk_leader_credit_transfers_debit (debit_transaction_id),
    UNIQUE KEY uk_leader_credit_transfers_credit (credit_transaction_id),
    KEY ix_leader_credit_transfers_leader (leader_id, created_at),
    KEY ix_leader_credit_transfers_player (player_id, created_at),
    CONSTRAINT fk_leader_credit_transfers_leader FOREIGN KEY (leader_id) REFERENCES users (id),
    CONSTRAINT fk_leader_credit_transfers_player FOREIGN KEY (player_id) REFERENCES users (id),
    CONSTRAINT fk_leader_credit_transfers_debit FOREIGN KEY (debit_transaction_id) REFERENCES wallet_transactions (id),
    CONSTRAINT fk_leader_credit_transfers_credit FOREIGN KEY (credit_transaction_id) REFERENCES wallet_transactions (id),
    CONSTRAINT ck_leader_credit_transfers_credits CHECK (credits > 0),
    CONSTRAINT ck_leader_credit_transfers_self CHECK (leader_id <> player_id)
) ENGINE = InnoDB;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('leader.transfer_min_credits', '10', 'Montant minimum, en credits, d une recharge d un joueur par son leader', NOW(6), NOW(6)),
    ('leader.transfer_max_credits', '5000', 'Montant maximum, en credits, d une recharge d un joueur par son leader', NOW(6), NOW(6));
