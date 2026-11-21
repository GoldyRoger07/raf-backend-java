-- Invitation par un filleul : le nouvel inscrit rejoint le leader de ce filleul
-- (toute la chaine, voir InviteService), avec le bonus de bienvenue de ce leader.
--
-- invited_by_id : le filleul dont le lien a servi. NULL quand le joueur a ete ajoute
-- par un administrateur ou s'est inscrit avec le lien du leader lui-meme.
ALTER TABLE leader_players
    ADD COLUMN invited_by_id BIGINT NULL,
    ADD CONSTRAINT fk_leader_players_invited_by FOREIGN KEY (invited_by_id) REFERENCES users (id);
