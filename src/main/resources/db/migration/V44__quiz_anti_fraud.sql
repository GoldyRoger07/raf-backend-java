-- Anti-fraude reglable par quiz solo (voir V43__anti_fraud.sql).
--
-- Active par defaut, donc rien ne change pour les quiz existants : sur un quiz coche,
-- les joueurs vises (liste anti-fraude ou interrupteur « pour tous ») ne peuvent pas
-- faire de sans-faute. Decoche : personne n'a l'anti-fraude sur ce quiz, meme les
-- joueurs de la liste. Modifiable a tout moment ; lu au lancement de chaque partie
-- (game_sessions.anti_fraud), les parties deja lancees gardent leur etat.
ALTER TABLE quizzes
    ADD COLUMN anti_fraud BOOLEAN NOT NULL DEFAULT TRUE AFTER perfect_cooldown_minutes;
