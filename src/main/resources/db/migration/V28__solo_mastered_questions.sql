-- Questions deja reussies en solo, par joueur et par sujet.
--
-- Une partie solo terminee (SUBMITTED) inscrit ici chaque question bien repondue ;
-- le tirage solo suivant du joueur sur ce sujet les ecarte. Une question ratee ou
-- sans reponse n'y entre pas et reste tirable.
--
-- Quand le vivier du quiz ne compte plus assez de questions jamais reussies pour
-- composer une partie, la liste est consideree pleine : le tirage retire alors les
-- entrees les plus anciennes (mastered_at), juste ce qu'il faut, et les ressert.
-- Une nouvelle reussite remet mastered_at a jour : l'entree repasse en fin de file.
CREATE TABLE solo_mastered_questions (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    category_id BIGINT      NOT NULL,
    question_id BIGINT      NOT NULL,
    session_id  BIGINT,
    mastered_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_solo_mastered (user_id, category_id, question_id),
    KEY ix_solo_mastered_age (user_id, category_id, mastered_at, id),
    CONSTRAINT fk_solo_mastered_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_solo_mastered_category FOREIGN KEY (category_id)
        REFERENCES categories (id) ON DELETE CASCADE,
    CONSTRAINT fk_solo_mastered_question FOREIGN KEY (question_id)
        REFERENCES questions (id) ON DELETE CASCADE,
    CONSTRAINT fk_solo_mastered_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE SET NULL
) ENGINE = InnoDB;
