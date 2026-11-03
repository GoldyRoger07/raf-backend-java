-- Quiz et comptes de test : exclus du resultat du jeu et de l'activite du tableau de
-- bord. Un quiz de test n'est visible que du staff et des comptes de test. Les soldes
-- des portefeuilles et le rapprochement, eux, comptent tout : l'argent reste reel.
ALTER TABLE quizzes ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
