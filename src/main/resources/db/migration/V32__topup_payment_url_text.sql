-- En live, MonCash renvoie un lien de redirection portant un long jeton, au-dela
-- des 500 caracteres prevus : l'ecriture echouait et la recharge en ligne
-- repondait 409 au joueur.
ALTER TABLE topup_requests MODIFY COLUMN payment_url TEXT;
