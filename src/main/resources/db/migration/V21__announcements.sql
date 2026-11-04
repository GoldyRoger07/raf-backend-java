-- =============================================================================
-- RAF - Messages du back-office (annonces) : a un joueur ou a tous les joueurs,
-- tout de suite ou a une date programmee, par popup dans l'app, push et/ou email.
--
-- Les destinataires sont figes au depart de l'envoi (announcement_recipients),
-- un par ligne avec son statut : un redemarrage du serveur en pleine diffusion
-- reprend la ou il s'etait arrete, sans renvoyer le message a ceux qui l'ont eu.
-- =============================================================================

CREATE TABLE announcements (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    title            VARCHAR(120)  NOT NULL,
    body             VARCHAR(1000) NOT NULL,
    -- Chemin dans l'app (ex. /quiz/12) : bouton de l'email, cible du push et du popup.
    link_path        VARCHAR(255),
    audience         VARCHAR(20)   NOT NULL,
    target_user_id   BIGINT,
    include_test     BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Message de service (compte, argent, securite) : passe outre les preferences
    -- email / push du joueur. Sinon, une annonce respecte ses choix.
    important        BOOLEAN       NOT NULL DEFAULT FALSE,
    channel_popup    BOOLEAN       NOT NULL,
    channel_push     BOOLEAN       NOT NULL,
    channel_email    BOOLEAN       NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    scheduled_at     DATETIME(6)   NOT NULL,
    popup_expires_at DATETIME(6),
    created_by       BIGINT        NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    started_at       DATETIME(6),
    completed_at     DATETIME(6),
    cancelled_by     BIGINT,
    cancelled_at     DATETIME(6),
    PRIMARY KEY (id),
    KEY ix_announcements_due (status, scheduled_at),
    KEY ix_announcements_created (created_at),
    CONSTRAINT fk_announcements_target FOREIGN KEY (target_user_id) REFERENCES users (id),
    CONSTRAINT fk_announcements_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_announcements_canceller FOREIGN KEY (cancelled_by) REFERENCES users (id)
) ENGINE = InnoDB;

CREATE TABLE announcement_recipients (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    announcement_id BIGINT      NOT NULL,
    user_id         BIGINT      NOT NULL,
    status          VARCHAR(10) NOT NULL,
    -- Notification in-app creee pour ce joueur : sert a suivre l'acquittement du popup.
    -- Effacee avec les notifications d'un compte supprime.
    notification_id BIGINT,
    email_sent      BOOLEAN     NOT NULL DEFAULT FALSE,
    push_sent       BOOLEAN     NOT NULL DEFAULT FALSE,
    processed_at    DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_announcement_recipients (announcement_id, user_id),
    KEY ix_announcement_recipients_pending (announcement_id, status),
    CONSTRAINT fk_announcement_recipients_announcement FOREIGN KEY (announcement_id)
        REFERENCES announcements (id) ON DELETE CASCADE,
    CONSTRAINT fk_announcement_recipients_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_announcement_recipients_notification FOREIGN KEY (notification_id)
        REFERENCES notifications (id) ON DELETE SET NULL
) ENGINE = InnoDB;

-- Popup : notification a acquitter (bouton "J'ai compris"), affichee a l'ouverture
-- de l'app tant qu'elle n'est ni acquittee ni expiree.
ALTER TABLE notifications
    ADD COLUMN requires_ack    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN acknowledged_at DATETIME(6),
    ADD COLUMN expires_at      DATETIME(6),
    ADD KEY ix_notifications_popups (user_id, requires_ack, acknowledged_at);
