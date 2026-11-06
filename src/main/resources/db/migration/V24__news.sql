-- Actualites : publications de l'equipe affichees dans l'onglet Actualite de
-- l'accueil de l'app joueur. Un brouillon n'est visible que du back-office.
CREATE TABLE news_posts (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    title         VARCHAR(140)  NOT NULL,
    body          TEXT          NOT NULL,
    media_id      BIGINT,
    -- Epinglee : affichee avant les autres, quelle que soit sa date.
    pinned        BOOLEAN       NOT NULL DEFAULT FALSE,
    published     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Premiere publication ; garde si l'actualite est depubliee puis republiee.
    published_at  DATETIME(6),
    created_by_id BIGINT,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_news_posts_feed (published, pinned, published_at),
    CONSTRAINT fk_news_posts_media FOREIGN KEY (media_id) REFERENCES media_files (id) ON DELETE SET NULL,
    CONSTRAINT fk_news_posts_author FOREIGN KEY (created_by_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB;
