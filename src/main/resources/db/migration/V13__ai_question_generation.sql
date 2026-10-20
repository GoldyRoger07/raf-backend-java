-- =============================================================================
-- V13 : generation de questions par IA
--
-- La generation n'est qu'un moyen de plus d'alimenter la banque, a cote de la
-- saisie et de l'import CSV : les questions generees arrivent en brouillon et
-- ne servent qu'une fois publiees par un humain.
-- =============================================================================

-- Origine de la question. Les questions existantes ne peuvent pas etre
-- distinguees apres coup (saisie ou import) : elles restent MANUAL.
ALTER TABLE questions
    ADD COLUMN source   VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' AFTER lang,
    ADD COLUMN ai_model VARCHAR(100) NULL AFTER source;

CREATE INDEX idx_questions_source_status ON questions (source, status);

-- Chaque demande de generation, reussie ou non : sert au plafond journalier
-- (chaque appel coute, meme s'il ne produit rien) et a comparer les modeles.
CREATE TABLE ai_generation_runs (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id       BIGINT       NOT NULL,
    category_id    BIGINT       NOT NULL,
    provider       VARCHAR(30)  NOT NULL,
    model          VARCHAR(100) NOT NULL,
    requested      INT          NOT NULL,
    created_count  INT          NOT NULL DEFAULT 0,
    duplicates     INT          NOT NULL DEFAULT 0,
    rejected       INT          NOT NULL DEFAULT 0,
    error          VARCHAR(500) NULL,
    duration_ms    BIGINT       NOT NULL DEFAULT 0,
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_generation_runs_created (created_at),
    CONSTRAINT fk_ai_generation_runs_category FOREIGN KEY (category_id) REFERENCES categories (id)
) ENGINE = InnoDB;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('ai.max_questions_per_request', '30', 'Nombre maximum de questions par demande de generation IA', NOW(6), NOW(6)),
    ('ai.max_requests_per_day', '20', 'Nombre maximum de demandes de generation IA par jour (heure d Haiti)', NOW(6), NOW(6));
