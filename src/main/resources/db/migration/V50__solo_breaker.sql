-- Disjoncteur de rentabilite des quiz solo (SoloBreakerService).
--
-- Chaque minute, pour chaque quiz solo ouvert, payant, ni de test ni masque : sur ses N
-- dernieres parties terminees (comptes de test exclus), si les prix verses depassent X %
-- des recettes, le quiz est masque aux joueurs (Quiz#hidden, jamais ferme) et les admins
-- sont prevenus. Reglages sur l'ecran Anti-fraude : app.solo_breaker.enabled (coupe par
-- defaut), app.solo_breaker.window_games (50), app.solo_breaker.min_games (20),
-- app.solo_breaker.max_payout_percent (100).
--
-- breaker_tripped_at : date du dernier declenchement, tant que le quiz reste masque.
-- breaker_counted_since : quand un admin demasque un quiz coupe par le disjoncteur, le
-- compte repart de zero : seules les parties lancees ensuite comptent.
ALTER TABLE quizzes
    ADD COLUMN breaker_tripped_at    DATETIME(6) NULL,
    ADD COLUMN breaker_counted_since DATETIME(6) NULL;

CREATE INDEX ix_game_sessions_quiz_started ON game_sessions (quiz_id, started_at);
