-- =============================================================================
-- RAF - Chrono par question et mode de deroulement choisi par quiz
--
-- Le chrono unique de partie est remplace par un chrono par question. Chaque
-- quiz choisit son deroulement :
--   BATCH      : tout le tirage au demarrage, soumission groupee, chrono tenu
--                par le client et controle a posteriori ;
--   SEQUENTIAL : une question a la fois, chaque question horodatee par le
--                serveur, qui refuse une reponse hors delai.
-- =============================================================================

-- Les quiz existants passent en BATCH, le comportement le plus proche de
-- l'ancien, avec la duree de partie repartie entre les questions.
ALTER TABLE quizzes
    ADD COLUMN play_mode                 VARCHAR(20) NOT NULL DEFAULT 'SEQUENTIAL' AFTER started_at,
    ADD COLUMN question_duration_seconds INT         NOT NULL DEFAULT 5 AFTER play_mode;

UPDATE quizzes
SET play_mode = 'BATCH',
    question_duration_seconds = GREATEST(3, CEIL(game_duration_seconds / GREATEST(1, questions_per_game)));

ALTER TABLE quizzes DROP COLUMN game_duration_seconds;

-- Mode et chrono sont copies dans la partie au demarrage, comme la duree et le
-- bareme : une partie se termine avec les regles sous lesquelles elle a
-- commence. duration_seconds reste la duree nominale (N x chrono par question).
--
-- Une partie existante a ete jouee avec un chrono global : son "chrono par
-- question" est donc la duree entiere, ce qui neutralise le nouveau controle.
ALTER TABLE game_sessions
    ADD COLUMN play_mode                 VARCHAR(20) NOT NULL DEFAULT 'BATCH' AFTER status,
    ADD COLUMN question_duration_seconds INT         NOT NULL DEFAULT 0 AFTER duration_seconds;

UPDATE game_sessions SET question_duration_seconds = duration_seconds;

ALTER TABLE game_sessions
    ALTER COLUMN play_mode DROP DEFAULT,
    ALTER COLUMN question_duration_seconds DROP DEFAULT;

-- SEQUENTIAL : instant ou la question a ete envoyee au joueur. Son chrono part
-- de la ; une question jamais servie reste a NULL.
ALTER TABLE game_session_questions
    ADD COLUMN served_at DATETIME(6) AFTER correct_choice_id;

-- Une question laissee sans reponse a l'expiration de son chrono est
-- enregistree sans choix : elle compte comme fausse.
ALTER TABLE game_answers
    MODIFY COLUMN choice_id BIGINT NULL;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('game.question_grace_ms', '1500',
     'Mode SEQUENTIAL : marge accordee a chaque reponse apres la fin du chrono de la question, pour absorber la latence reseau',
     NOW(6), NOW(6));
