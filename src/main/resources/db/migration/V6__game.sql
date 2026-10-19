-- =============================================================================
-- RAF - Lot 6 : parties, reponses, classement et versement des gains
--
-- Modele anti-triche retenu : revelation differee. Le corrige ne quitte jamais
-- le serveur pendant la partie ; le score est recalcule au submit a partir du
-- tirage fige ici, et le detail des reponses n'est accessible qu'une fois le
-- quiz termine.
-- =============================================================================

-- 1 ticket = 1 partie : ticket_id est UNIQUE, c'est la base qui l'impose.
--
-- duration_seconds et points_per_correct sont copies depuis le quiz au
-- demarrage. Le submit n'a ainsi jamais besoin de charger le quiz, ce qui
-- garantit que le verrou pris pour declencher la cloture est bien la premiere
-- lecture du quiz dans la transaction.
CREATE TABLE game_sessions (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    quiz_id             BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    ticket_id           BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    duration_seconds    INT          NOT NULL,
    points_per_correct  INT          NOT NULL,
    started_at          DATETIME(6)  NOT NULL,
    server_deadline_at  DATETIME(6)  NOT NULL,
    submitted_at        DATETIME(6),
    client_duration_ms  BIGINT,
    score               INT,
    correct_count       INT,
    answered_count      INT,
    invalidation_reason VARCHAR(255),
    flagged             BOOLEAN      NOT NULL DEFAULT FALSE,
    flag_reason         VARCHAR(255),
    ip                  VARCHAR(45),
    user_agent          VARCHAR(255),
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_sessions_ticket (ticket_id),
    KEY ix_game_sessions_quiz_status (quiz_id, status, score),
    KEY ix_game_sessions_user_quiz (user_id, quiz_id),
    KEY ix_game_sessions_deadline (status, server_deadline_at),
    CONSTRAINT fk_game_sessions_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_game_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_game_sessions_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id)
) ENGINE = InnoDB;

-- Le tirage fige du joueur. correct_choice_id est un instantane : corriger une
-- question apres coup ne change pas un score deja calcule.
CREATE TABLE game_session_questions (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    session_id        BIGINT NOT NULL,
    question_id       BIGINT NOT NULL,
    position          INT    NOT NULL,
    correct_choice_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_gsq_position (session_id, position),
    UNIQUE KEY uk_gsq_question (session_id, question_id),
    CONSTRAINT fk_gsq_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_gsq_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE = InnoDB;

CREATE TABLE game_answers (
    id             BIGINT  NOT NULL AUTO_INCREMENT,
    session_id     BIGINT  NOT NULL,
    question_id    BIGINT  NOT NULL,
    choice_id      BIGINT  NOT NULL,
    is_correct     BOOLEAN NOT NULL,
    elapsed_ms     BIGINT  NOT NULL,
    points_awarded INT     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_game_answers (session_id, question_id),
    CONSTRAINT fk_game_answers_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_game_answers_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE = InnoDB;

-- Classement fige a la cloture. Tri : score DESC, first_started_at ASC.
-- "rank" est un mot reserve depuis MySQL 8.0.2, d'ou rank_position.
CREATE TABLE quiz_rankings (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    quiz_id          BIGINT      NOT NULL,
    user_id          BIGINT      NOT NULL,
    best_session_id  BIGINT      NOT NULL,
    score            INT         NOT NULL,
    first_started_at DATETIME(6) NOT NULL,
    rank_position    INT         NOT NULL,
    reward_credits   BIGINT      NOT NULL DEFAULT 0,
    paid_at          DATETIME(6),
    transaction_id   BIGINT,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quiz_rankings_user (quiz_id, user_id),
    UNIQUE KEY uk_quiz_rankings_rank (quiz_id, rank_position),
    CONSTRAINT fk_quiz_rankings_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_quiz_rankings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_quiz_rankings_session FOREIGN KEY (best_session_id) REFERENCES game_sessions (id)
) ENGINE = InnoDB;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('game.network_grace_seconds', '15',
     'Marge accordee au submit apres la fin du chrono, pour absorber la latence reseau', NOW(6), NOW(6)),
    ('game.min_answer_ms', '400',
     'En dessous de cet intervalle entre deux reponses, le temps de reaction est juge suspect', NOW(6), NOW(6)),
    ('game.fast_answer_flag_count', '5',
     'Nombre de reponses suspectes a partir duquel une partie est signalee', NOW(6), NOW(6));
