-- Anti-fraude : les trois reglages se combinent par OU (voir V43 et V44).
--
-- Un joueur (hors staff) a l'anti-fraude sur une partie solo si l'interrupteur
-- « pour tous » est active, OU s'il est dans la liste anti-fraude, OU si la case
-- anti-fraude du quiz est cochee. La case du quiz n'est donc plus un moyen de couper
-- l'anti-fraude : cochee, elle vise tous les joueurs de ce quiz.
--
-- Pour que rien ne change a la mise en prod, tous les quiz sont decoches et les
-- nouveaux quiz le sont par defaut. Les parties deja lancees gardent leur etat.
UPDATE quizzes SET anti_fraud = FALSE;
ALTER TABLE quizzes ALTER COLUMN anti_fraud SET DEFAULT FALSE;
