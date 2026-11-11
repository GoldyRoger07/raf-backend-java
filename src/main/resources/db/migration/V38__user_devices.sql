-- Appareils vus par compte (en-tete X-Device-Id des requetes authentifiees).
--
-- Deux comptes vus sur un meme appareil sont lies. La limite d'une partie solo par
-- sujet et par jour s'applique a tout le groupe : vider les donnees du navigateur
-- donne un nouvel identifiant a l'appareil, mais les comptes deja vus gardent les
-- leurs et restent lies. Le back-office montre ces liens sur la fiche joueur et
-- dans la file des retraits.
CREATE TABLE user_devices (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    device_id     VARCHAR(64) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_devices (user_id, device_id),
    KEY ix_user_devices_device (device_id, user_id),
    CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Alerte de la file des retraits : numero de reception deja utilise par un autre compte.
ALTER TABLE withdrawal_requests
    ADD KEY ix_withdrawal_requests_destination (destination_number, user_id);
