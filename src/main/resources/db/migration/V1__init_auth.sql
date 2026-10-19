-- =============================================================================
-- RAF - Lot 1 & 2 : fondations et authentification
-- =============================================================================

CREATE TABLE app_settings (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(120) NOT NULL,
    setting_value VARCHAR(1000),
    description   VARCHAR(255),
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_settings_key (setting_key)
) ENGINE = InnoDB;

CREATE TABLE users (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(180) NOT NULL,
    phone             VARCHAR(20)  NOT NULL,
    password_hash     VARCHAR(100) NOT NULL,
    first_name        VARCHAR(80)  NOT NULL,
    last_name         VARCHAR(80)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    email_verified_at DATETIME(6),
    phone_verified_at DATETIME(6),
    last_login_at     DATETIME(6),
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_phone (phone)
) ENGINE = InnoDB;

CREATE TABLE roles (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    name        VARCHAR(40) NOT NULL,
    description VARCHAR(255),
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_name (name)
) ENGINE = InnoDB;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Rotation avec detection de reutilisation : rejouer un token deja consomme
-- revoque toute la famille (indice fort de vol de token).
CREATE TABLE refresh_tokens (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    token_hash      CHAR(64)     NOT NULL,
    family_id       CHAR(36)     NOT NULL,
    expires_at      DATETIME(6)  NOT NULL,
    revoked_at      DATETIME(6),
    revoked_reason  VARCHAR(40),
    replaced_by_id  BIGINT,
    device_label    VARCHAR(120),
    user_agent      VARCHAR(255),
    ip_address      VARCHAR(45),
    created_at      DATETIME(6)  NOT NULL,
    last_used_at    DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY ix_refresh_tokens_user (user_id),
    KEY ix_refresh_tokens_family (family_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE TABLE verification_tokens (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    type        VARCHAR(30) NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_tokens_hash (token_hash),
    KEY ix_verification_tokens_user (user_id, type),
    CONSTRAINT fk_verification_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Codes a 6 chiffres : l'espace est minuscule, donc expiration courte,
-- nombre d'essais plafonne et delai anti-spam entre deux envois.
CREATE TABLE otp_codes (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    purpose     VARCHAR(30) NOT NULL,
    code_hash   VARCHAR(100) NOT NULL,
    destination VARCHAR(120),
    expires_at  DATETIME(6) NOT NULL,
    consumed_at DATETIME(6),
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_otp_codes_user_purpose (user_id, purpose, created_at),
    CONSTRAINT fk_otp_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- Journal d'audit : trace des actions sensibles, jamais modifie apres insertion.
CREATE TABLE audit_logs (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT,
    action        VARCHAR(60) NOT NULL,
    entity_type   VARCHAR(60),
    entity_id     VARCHAR(60),
    before_state  TEXT,
    after_state   TEXT,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(255),
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_audit_logs_actor (actor_user_id, created_at),
    KEY ix_audit_logs_entity (entity_type, entity_id),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB;

INSERT INTO roles (name, description) VALUES
    ('PLAYER',      'Joueur : achat de tickets, participation aux quiz'),
    ('MODERATOR',   'Moderateur : gestion de la banque de questions'),
    ('ADMIN',       'Administrateur : quiz, recompenses, validation des retraits'),
    ('SUPER_ADMIN', 'Acces total, y compris la gestion des administrateurs');

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('credit.htg_rate', '5', 'Valeur d un credit en gourdes', NOW(6), NOW(6)),
    ('game.pool_ratio_min', '10', 'Taille minimale du pool par rapport aux questions servies', NOW(6), NOW(6));
