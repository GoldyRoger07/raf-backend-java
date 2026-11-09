-- Quiz solo : fermeture automatique au premier sans-faute.
--
-- Choisi par l'admin a la creation du quiz. Quand une partie se termine sans faute
-- (et donne lieu au versement du prix), le quiz passe aussitot de RUNNING a
-- FINISHED : plus aucune partie ne peut y etre lancee. Les parties deja lancees
-- vont a leur terme, comme a une fermeture manuelle.
ALTER TABLE quizzes
    ADD COLUMN close_on_perfect BOOLEAN NOT NULL DEFAULT FALSE AFTER solo_prize_htg;
