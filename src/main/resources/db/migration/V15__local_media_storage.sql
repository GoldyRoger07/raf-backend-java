-- =============================================================================
-- RAF - Medias stockes sur le serveur
--
-- Les images ne sont plus hebergees par ImageKit : le backend les ecrit sur son
-- disque (un volume en Docker) et nginx les sert. La base garde le chemin du
-- fichier dans le dossier des medias (2026/10/<uuid>.jpg), jamais une URL
-- complete : l'adresse publique vient de la configuration, et changer de
-- domaine ne demande aucune migration.
--
-- Une ligne heritee d'ImageKit garde son URL complete dans storage_key : elle
-- reste affichee telle quelle (MediaUrls laisse passer les adresses http).
-- =============================================================================

ALTER TABLE media_files
    DROP INDEX uk_media_imagekit_id,
    DROP COLUMN imagekit_file_id,
    DROP COLUMN file_path,
    RENAME COLUMN url TO storage_key,
    RENAME COLUMN thumbnail_url TO thumbnail_key,
    ADD COLUMN width  INT AFTER size_bytes,
    ADD COLUMN height INT AFTER width,
    ADD UNIQUE KEY uk_media_storage_key (storage_key);
