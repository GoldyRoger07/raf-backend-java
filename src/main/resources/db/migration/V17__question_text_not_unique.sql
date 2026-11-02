-- =============================================================================
-- RAF - Enonces identiques autorises
--
-- Deux questions d'une meme categorie peuvent partager un enonce (ex. "Qui est
-- sur cette image ?" avec des medias differents). L'unicite n'est plus imposee
-- en base ; l'import CSV et la generation IA ecartent toujours les doublons au
-- niveau applicatif. L'index reste, non unique, pour cette verification.
-- =============================================================================

ALTER TABLE questions
    ADD KEY ix_questions_category_text (category_id, text_hash),
    DROP INDEX uk_questions_category_text;
