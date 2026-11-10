-- Quiz masque aux joueurs, sans changer son statut.
--
-- Un quiz masque disparait des listes de l'app joueur et n'accepte plus de nouvelle
-- entree (achat de ticket, nouvelle partie solo). Les joueurs qui ont deja un ticket
-- le voient toujours et jouent normalement ; le staff et les comptes de test le voient
-- aussi. L'argent et le cycle de vie du quiz ne sont pas touches : on le demasque d'un clic.
ALTER TABLE quizzes
    ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE AFTER is_test;
