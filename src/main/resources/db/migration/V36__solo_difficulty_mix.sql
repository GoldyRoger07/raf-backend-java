-- Quiz solo : repartition des questions d'une partie par difficulte.
--
-- Les trois colonnes sont vides ensemble (tirage libre dans le vivier, comme avant) ou
-- remplies ensemble, de 0 a 100, pour un total de 100. Le nombre de questions par niveau
-- se deduit a chaque partie (plus forts restes, egalite au plus difficile). Reglable a
-- tout moment, y compris sur un quiz ouvert : seules les parties suivantes sont touchees.
ALTER TABLE quizzes
    ADD COLUMN solo_easy_percent INT NULL AFTER perfect_cooldown_minutes,
    ADD COLUMN solo_medium_percent INT NULL AFTER solo_easy_percent,
    ADD COLUMN solo_hard_percent INT NULL AFTER solo_medium_percent;
