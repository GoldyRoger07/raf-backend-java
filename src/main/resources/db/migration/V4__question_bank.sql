-- =============================================================================
-- RAF - Lot 4 : medias (ImageKit), categories et banque de questions
-- =============================================================================

-- Les fichiers sont heberges par ImageKit ; la base ne garde que les references
-- necessaires pour afficher et pour supprimer cote ImageKit.
CREATE TABLE media_files (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    imagekit_file_id VARCHAR(120) NOT NULL,
    file_path        VARCHAR(500),
    url              VARCHAR(500) NOT NULL,
    thumbnail_url    VARCHAR(500),
    mime_type        VARCHAR(100),
    size_bytes       BIGINT,
    folder           VARCHAR(120),
    uploaded_by      BIGINT,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_imagekit_id (imagekit_file_id),
    KEY ix_media_uploader (uploaded_by, created_at)
) ENGINE = InnoDB;

ALTER TABLE users
    ADD COLUMN avatar_media_id BIGINT NULL AFTER last_name,
    ADD CONSTRAINT fk_users_avatar FOREIGN KEY (avatar_media_id)
        REFERENCES media_files (id) ON DELETE SET NULL;

CREATE TABLE categories (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(140) NOT NULL,
    description VARCHAR(500),
    parent_id   BIGINT,
    media_id    BIGINT,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_slug (slug),
    KEY ix_categories_parent (parent_id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT fk_categories_media FOREIGN KEY (media_id) REFERENCES media_files (id) ON DELETE SET NULL
) ENGINE = InnoDB;

-- text_hash : MySQL ne peut pas indexer une colonne TEXT entiere. L'empreinte de
-- l'enonce normalise permet d'imposer l'unicite par categorie, ce qui bloque les
-- doublons lors des imports CSV successifs.
CREATE TABLE questions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    category_id   BIGINT       NOT NULL,
    text          TEXT         NOT NULL,
    text_hash     CHAR(64)     NOT NULL,
    type          VARCHAR(20)  NOT NULL,
    difficulty    VARCHAR(10)  NOT NULL,
    media_id      BIGINT,
    explanation   VARCHAR(1000),
    status        VARCHAR(20)  NOT NULL,
    lang          CHAR(2)      NOT NULL DEFAULT 'fr',
    times_served  INT          NOT NULL DEFAULT 0,
    times_correct INT          NOT NULL DEFAULT 0,
    created_by    BIGINT,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_questions_category_text (category_id, text_hash),
    KEY ix_questions_pool (category_id, status, difficulty),
    CONSTRAINT fk_questions_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_questions_media FOREIGN KEY (media_id) REFERENCES media_files (id) ON DELETE SET NULL
) ENGINE = InnoDB;

CREATE TABLE question_choices (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    question_id BIGINT       NOT NULL,
    text        VARCHAR(500) NOT NULL,
    is_correct  BOOLEAN      NOT NULL DEFAULT FALSE,
    position    INT          NOT NULL,
    media_id    BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_choices_question_position (question_id, position),
    KEY ix_choices_question (question_id),
    CONSTRAINT fk_choices_question FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_choices_media FOREIGN KEY (media_id) REFERENCES media_files (id) ON DELETE SET NULL
) ENGINE = InnoDB;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('question.import_max_rows', '2000',
     'Nombre maximum de lignes acceptees par import CSV', NOW(6), NOW(6));
