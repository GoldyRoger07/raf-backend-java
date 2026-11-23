-- Entrainement gratuit et progression du joueur (XP, niveaux, missions, badges).
--
-- Entrainement : sujets reserves (categories.practice_enabled). Un sujet d'entrainement
-- n'est jamais propose en solo ni en groupe : la correction y est montree aussitot, ses
-- questions ne doivent jamais servir a une partie payante. Gratuit, sans gain, limite par
-- jour (app.practice.daily_limit, 3 par defaut).
--
-- La progression ne stocke rien : XP, niveau, missions et badges se calculent a partir des
-- parties (game_sessions) et des entrainements. Elle ne rapporte ni credit ni gourde.
ALTER TABLE categories
    ADD COLUMN practice_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE practice_sessions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    category_id     BIGINT       NOT NULL,
    question_count  INT          NOT NULL,
    correct_count   INT          NOT NULL DEFAULT 0,
    answered_count  INT          NOT NULL DEFAULT 0,
    started_at      DATETIME(6)  NOT NULL,
    finished_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY ix_practice_sessions_user (user_id, started_at),
    CONSTRAINT fk_practice_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_sessions_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB;

CREATE TABLE practice_questions (
    session_id   BIGINT       NOT NULL,
    position     INT          NOT NULL,
    question_id  BIGINT       NOT NULL,
    choice_id    BIGINT       NULL,
    correct      BOOLEAN      NULL,
    answered_at  DATETIME(6)  NULL,
    PRIMARY KEY (session_id, position),
    UNIQUE KEY uk_practice_questions_question (session_id, question_id),
    CONSTRAINT fk_practice_questions_session FOREIGN KEY (session_id) REFERENCES practice_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_questions_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE = InnoDB;
