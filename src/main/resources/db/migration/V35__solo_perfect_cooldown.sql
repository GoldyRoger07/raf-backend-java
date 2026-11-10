-- Quiz solo : cache au joueur pendant un delai apres son sans-faute.
--
-- perfect_cooldown_minutes : vide = regle desactivee. Sinon, un joueur (compte de test
-- compris, staff exclu) qui a fait un sans-faute sur ce quiz ne le voit plus pendant ce
-- delai, compte depuis son dernier sans-faute. Reglable a tout moment, y compris sur un
-- quiz ouvert : la regle ne touche ni aux prix ni aux gains.
ALTER TABLE quizzes
    ADD COLUMN perfect_cooldown_minutes INT NULL AFTER close_on_perfect;

-- Sans-faute memorise sur la partie (avant, seul le prix verse le disait, et jamais pour
-- un compte de test). Les parties passees sont reprises : une partie solo validee dont
-- toutes les questions servies sont justes.
ALTER TABLE game_sessions
    ADD COLUMN perfect BOOLEAN NOT NULL DEFAULT FALSE AFTER won;

UPDATE game_sessions s
SET s.perfect = TRUE
WHERE s.game_mode = 'SOLO'
  AND s.status = 'SUBMITTED'
  AND s.correct_count > 0
  AND s.correct_count = (SELECT COUNT(*) FROM game_session_questions q WHERE q.session_id = s.id);

CREATE INDEX idx_game_sessions_perfect ON game_sessions (user_id, perfect, quiz_id, submitted_at);
