-- Cashback du quasi sans-faute en solo (SoloCashbackService).
--
-- Partie solo payante terminee avec exactement une erreur (9/10, 19/20...) : une part du
-- prix d'entree est rendue en credits (solde CREDIT, non retirable), transaction
-- SOLO_CASHBACK. Exclus : parties offertes ou de test, parties ou l'anti-fraude a compte
-- des reponses fausses, joueurs sur la liste anti-fraude ou sous profil fraudeur.
--
-- Reglages globaux (ecran App joueur) : app.solo_cashback.enabled (coupe par defaut),
-- app.solo_cashback.percent (50), app.solo_cashback.daily_limit (3 par joueur et par
-- jour), app.solo_cashback.min_questions (5).
--
-- quizzes.solo_cashback_percent : null = taux global, 0 = pas de cashback sur ce quiz,
-- 1 a 100 = taux propre au quiz.
-- game_sessions.cashback_credits / cashback_transaction_id : credits rendus et leur
-- mouvement ; null sans cashback. Les recettes d'un quiz (disjoncteur, statistiques)
-- sont les entrees payees moins ces credits.
ALTER TABLE quizzes
    ADD COLUMN solo_cashback_percent INT NULL;

ALTER TABLE game_sessions
    ADD COLUMN cashback_credits        INT    NULL,
    ADD COLUMN cashback_transaction_id BIGINT NULL;

CREATE INDEX ix_game_sessions_user_day_cashback ON game_sessions (user_id, solo_day, cashback_credits);
