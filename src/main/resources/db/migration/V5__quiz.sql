-- =============================================================================
-- RAF - Lot 5 : quiz, sujets soumis au vote, recompenses et tickets
--
-- Cycle de vie :
--   DRAFT -> REGISTRATION -> SCHEDULED -> RUNNING -> CLOSING -> COMPUTING
--         -> FINISHED
--   (CANCELLED possible tant que la partie n'a pas demarre)
--
-- Le vote du sujet n'est PAS une etape du cycle mais une phase superposee a
-- REGISTRATION : il est reserve aux detenteurs de ticket, qui doivent donc
-- pouvoir acheter avant que le sujet precis soit connu. Seul le theme general
-- (theme_label) est annonce a ce moment-la.
-- =============================================================================

CREATE TABLE quizzes (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    title                    VARCHAR(160) NOT NULL,
    slug                     VARCHAR(180) NOT NULL,
    description              VARCHAR(1000),
    banner_media_id          BIGINT,
    theme_label              VARCHAR(160),
    status                   VARCHAR(20)  NOT NULL,
    topic_mode               VARCHAR(10)  NOT NULL,
    selected_category_id     BIGINT,

    ticket_price_credits     INT          NOT NULL,
    min_players              INT          NOT NULL,
    max_tickets_total        INT,
    max_tickets_per_player   INT,

    start_delay_minutes      INT          NOT NULL DEFAULT 30,
    threshold_reached_at     DATETIME(6),
    scheduled_start_at       DATETIME(6),
    started_at               DATETIME(6),

    game_duration_seconds    INT          NOT NULL DEFAULT 120,
    questions_per_game       INT          NOT NULL DEFAULT 40,
    points_per_correct       INT          NOT NULL DEFAULT 10,

    min_score_threshold      INT          NOT NULL,
    closing_countdown_minutes INT         NOT NULL DEFAULT 120,
    closing_triggered_at     DATETIME(6),
    ends_at                  DATETIME(6),
    finished_at              DATETIME(6),

    vote_opens_at            DATETIME(6),
    vote_closes_at           DATETIME(6),

    cancellation_reason      VARCHAR(255),
    created_by               BIGINT,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quizzes_slug (slug),
    KEY ix_quizzes_status (status, scheduled_start_at),
    CONSTRAINT fk_quizzes_category FOREIGN KEY (selected_category_id) REFERENCES categories (id),
    CONSTRAINT fk_quizzes_banner FOREIGN KEY (banner_media_id)
        REFERENCES media_files (id) ON DELETE SET NULL,
    CONSTRAINT ck_quizzes_price CHECK (ticket_price_credits > 0),
    CONSTRAINT ck_quizzes_min_players CHECK (min_players > 0)
) ENGINE = InnoDB;

CREATE TABLE quiz_topics (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    quiz_id     BIGINT       NOT NULL,
    category_id BIGINT       NOT NULL,
    label       VARCHAR(160) NOT NULL,
    media_id    BIGINT,
    vote_count  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quiz_topics (quiz_id, category_id),
    CONSTRAINT fk_quiz_topics_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id) ON DELETE CASCADE,
    CONSTRAINT fk_quiz_topics_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_quiz_topics_media FOREIGN KEY (media_id)
        REFERENCES media_files (id) ON DELETE SET NULL
) ENGINE = InnoDB;

-- Un vote par compte et par quiz : l'unicite est portee par la base, pas par le
-- code, pour resister aux envois concurrents.
CREATE TABLE topic_votes (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    quiz_id       BIGINT      NOT NULL,
    quiz_topic_id BIGINT      NOT NULL,
    user_id       BIGINT      NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_topic_votes_user (quiz_id, user_id),
    KEY ix_topic_votes_topic (quiz_topic_id),
    CONSTRAINT fk_topic_votes_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_votes_topic FOREIGN KEY (quiz_topic_id)
        REFERENCES quiz_topics (id) ON DELETE CASCADE,
    CONSTRAINT fk_topic_votes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Recompenses par tranche de rang : (1,1,10000) (2,2,5000) (4,10,500)...
-- Montants fixes definis par l'admin, en credits.
CREATE TABLE quiz_rewards (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    quiz_id        BIGINT       NOT NULL,
    rank_from      INT          NOT NULL,
    rank_to        INT          NOT NULL,
    amount_credits BIGINT       NOT NULL,
    label          VARCHAR(120),
    PRIMARY KEY (id),
    UNIQUE KEY uk_quiz_rewards_range (quiz_id, rank_from),
    CONSTRAINT fk_quiz_rewards_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id) ON DELETE CASCADE,
    CONSTRAINT ck_quiz_rewards_range CHECK (rank_from >= 1 AND rank_to >= rank_from),
    CONSTRAINT ck_quiz_rewards_amount CHECK (amount_credits > 0)
) ENGINE = InnoDB;

-- 1 ticket = 1 partie. idempotency_key protege du double clic : une seconde
-- requete portant la meme cle retrouve le ticket deja achete au lieu d'en
-- acheter un autre.
CREATE TABLE tickets (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    quiz_id              BIGINT      NOT NULL,
    user_id              BIGINT      NOT NULL,
    code                 VARCHAR(24) NOT NULL,
    price_paid_credits   INT         NOT NULL,
    status               VARCHAR(20) NOT NULL,
    transaction_id       BIGINT,
    refund_transaction_id BIGINT,
    idempotency_key      VARCHAR(120),
    purchased_at         DATETIME(6) NOT NULL,
    used_at              DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tickets_code (code),
    UNIQUE KEY uk_tickets_idempotency (idempotency_key),
    KEY ix_tickets_quiz_user (quiz_id, user_id),
    KEY ix_tickets_quiz_status (quiz_id, status),
    CONSTRAINT fk_tickets_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_tickets_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB;

-- Vivier fige au demarrage du quiz : les questions tirees pour chaque joueur
-- proviennent de cette liste, et non de la categorie, pour qu'une modification
-- editoriale en cours de quiz ne change pas les regles du jeu.
CREATE TABLE quiz_question_pool (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    quiz_id     BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quiz_pool (quiz_id, question_id),
    CONSTRAINT fk_quiz_pool_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes (id) ON DELETE CASCADE,
    CONSTRAINT fk_quiz_pool_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE = InnoDB;
