-- =============================================================================
-- RAF - Notifications : in-app, email et push navigateur (VAPID)
--
-- Toute notification est conservee in-app. L'email et le push sont envoyes
-- selon les canaux par defaut de son type, que le joueur peut couper type par
-- type (notification_preferences ne stocke que ses choix explicites).
-- =============================================================================

CREATE TABLE notifications (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,
    type       VARCHAR(40)   NOT NULL,
    title      VARCHAR(160)  NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    -- Donnees de navigation pour le frontend (identifiant du quiz, du retrait...),
    -- serialisees en JSON.
    data       VARCHAR(2000),
    read_at    DATETIME(6),
    created_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY ix_notifications_user (user_id, created_at),
    KEY ix_notifications_unread (user_id, read_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE notification_preferences (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    type       VARCHAR(40) NOT NULL,
    channel    VARCHAR(10) NOT NULL,
    enabled    BOOLEAN     NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notification_preferences (user_id, type, channel),
    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Un abonnement par navigateur. L'endpoint, fourni par le navigateur, peut
-- depasser la longueur indexable : l'unicite porte sur son empreinte.
CREATE TABLE push_subscriptions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    endpoint      TEXT         NOT NULL,
    endpoint_hash CHAR(64)     NOT NULL,
    p256dh        VARCHAR(200) NOT NULL,
    auth_key      VARCHAR(100) NOT NULL,
    user_agent    VARCHAR(255),
    created_at    DATETIME(6)  NOT NULL,
    last_used_at  DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_subscriptions_endpoint (endpoint_hash),
    KEY ix_push_subscriptions_user (user_id),
    CONSTRAINT fk_push_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;
