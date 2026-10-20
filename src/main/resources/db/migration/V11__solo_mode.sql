-- =============================================================================
-- RAF - Mode Solo
--
-- A cote des quiz de groupe (tickets, classement, recompenses par rang), un quiz
-- solo se joue en continu : chaque partie est payee en credits au lancement, et
-- un sans-faute rapporte le prix fixe par l'admin. Un joueur ne joue qu'une
-- partie par sujet et par jour (jour civil d'Haiti).
-- =============================================================================

-- Chaque sujet est propose, ou non, dans chaque mode.
ALTER TABLE categories
    ADD COLUMN group_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER active,
    ADD COLUMN solo_enabled  BOOLEAN NOT NULL DEFAULT TRUE AFTER group_enabled;

-- Quiz solo : ticket_price_credits est le prix d'une partie, solo_prize_credits
-- le prix d'un sans-faute. Les champs propres au groupe (seuil de joueurs,
-- score declencheur, compte a rebours) n'y servent pas.
ALTER TABLE quizzes
    ADD COLUMN game_mode          VARCHAR(10) NOT NULL DEFAULT 'GROUP' AFTER status,
    ADD COLUMN solo_prize_credits BIGINT AFTER points_per_correct;

-- Partie solo : pas de ticket, un debit d'entree et, en cas de sans-faute, un
-- versement. Prix d'entree et prix sont copies au lancement, comme le bareme.
--
-- uk_game_sessions_solo_day : une partie par joueur, par sujet et par jour. Les
-- parties de groupe laissent solo_day a NULL et echappent donc a la contrainte.
ALTER TABLE game_sessions
    ADD COLUMN game_mode            VARCHAR(10) NOT NULL DEFAULT 'GROUP' AFTER status,
    MODIFY COLUMN ticket_id         BIGINT NULL,
    ADD COLUMN category_id          BIGINT AFTER ticket_id,
    ADD COLUMN solo_day             DATE AFTER category_id,
    ADD COLUMN entry_price_credits  INT AFTER solo_day,
    ADD COLUMN entry_transaction_id BIGINT AFTER entry_price_credits,
    ADD COLUMN prize_credits        BIGINT AFTER entry_transaction_id,
    ADD COLUMN won                  BOOLEAN NOT NULL DEFAULT FALSE AFTER prize_credits,
    ADD COLUMN prize_transaction_id BIGINT AFTER won,
    ADD UNIQUE KEY uk_game_sessions_solo_day (user_id, category_id, solo_day),
    ADD UNIQUE KEY uk_game_sessions_entry_tx (entry_transaction_id),
    ADD CONSTRAINT fk_game_sessions_category FOREIGN KEY (category_id) REFERENCES categories (id);

ALTER TABLE game_sessions ALTER COLUMN game_mode DROP DEFAULT;
