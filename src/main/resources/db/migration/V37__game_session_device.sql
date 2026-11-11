-- Identifiant d'appareil de la partie (en-tete X-Device-Id envoye par l'app).
--
-- uk_game_sessions_solo_device_day : une partie solo par appareil, par sujet et par
-- jour, quel que soit le compte. Elle ferme la porte aux comptes multiples joues
-- depuis le meme telephone. Les parties de groupe et de test (solo_day NULL) et les
-- clients qui n'envoient pas l'en-tete (device_id NULL) y echappent : MySQL ne
-- compare pas les NULL dans un index unique.
ALTER TABLE game_sessions
    ADD COLUMN device_id VARCHAR(64) NULL AFTER user_agent,
    ADD UNIQUE KEY uk_game_sessions_solo_device_day (category_id, solo_day, device_id);
