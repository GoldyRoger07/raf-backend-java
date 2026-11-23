-- Quiz solo ouvert : prix, prime et nombre de questions modifiables (QuizService.changeSoloTerms).
--
-- Chaque partie garde deja ses propres valeurs (game_sessions.entry_price_credits, prize_htg,
-- questions tirees) : les parties passees et en cours ne changent pas, seules les suivantes
-- prennent les nouvelles regles.
--
-- leaderboard_since : quand le nombre de questions change, le classement du quiz repart de
-- zero (un score sur 10 et un score sur 15 ne se comparent pas) ; seules les parties lancees
-- depuis cette date y comptent. Null : toutes les parties.
ALTER TABLE quizzes
    ADD COLUMN leaderboard_since DATETIME(6) NULL;
