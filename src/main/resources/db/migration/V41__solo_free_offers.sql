-- Premiere partie solo offerte (interrupteur app.first_solo_free.enabled, coupe par defaut).
--
-- Une ligne par compte qui a recu sa partie offerte. La cle primaire tient la regle
-- « une seule fois par compte » meme pour deux lancements simultanes. L'offre est
-- refusee aussi quand un compte lie par appareil (user_devices) l'a deja recue ou a deja
-- paye une partie solo. La partie offerte garde le prix complet au sans-faute ; son
-- entry_price_credits vaut 0, donc ni debit d'entree ni commission de leader.
CREATE TABLE solo_free_offers (
    user_id     BIGINT      NOT NULL,
    session_id  BIGINT,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_solo_free_offers_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_solo_free_offers_session FOREIGN KEY (session_id)
        REFERENCES game_sessions (id) ON DELETE SET NULL
) ENGINE = InnoDB;
