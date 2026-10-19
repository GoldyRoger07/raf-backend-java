-- =============================================================================
-- RAF - Lot 3 : portefeuille, transactions, recharges et retraits
--
-- Tous les montants internes sont en CREDITS (entiers, jamais de flottant).
-- La conversion en gourdes se fait a l'affichage et au retrait, au taux stocke
-- dans app_settings (credit.htg_rate).
-- =============================================================================

-- Deux soldes separes : les credits achetes servent a jouer et ne sont pas
-- retirables ; seuls les gains le sont. Sans cette separation, recharger puis
-- retirer immediatement permettrait de blanchir de l'argent et d'abuser des
-- frais de paiement.
CREATE TABLE wallets (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    credit_balance   BIGINT      NOT NULL DEFAULT 0,
    winnings_balance BIGINT      NOT NULL DEFAULT 0,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallets_user (user_id),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_wallets_credit_positive CHECK (credit_balance >= 0),
    CONSTRAINT ck_wallets_winnings_positive CHECK (winnings_balance >= 0)
) ENGINE = InnoDB;

-- Journal immuable : une ligne par mouvement, jamais modifiee ni supprimee.
-- balance_after permet de rejouer l'historique et de detecter une incoherence.
CREATE TABLE wallet_transactions (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    wallet_id       BIGINT       NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    balance_type    VARCHAR(10)  NOT NULL,
    amount          BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    reference_type  VARCHAR(40),
    reference_id    VARCHAR(60),
    idempotency_key VARCHAR(120),
    description     VARCHAR(255),
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_tx_idempotency (idempotency_key),
    KEY ix_wallet_tx_wallet (wallet_id, created_at),
    KEY ix_wallet_tx_reference (reference_type, reference_id),
    CONSTRAINT fk_wallet_tx_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id)
) ENGINE = InnoDB;

-- Aucun credit n'est verse avant confirmation : une recharge naît PENDING et
-- n'alimente le solde qu'au passage en CONFIRMED.
CREATE TABLE topup_requests (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    user_id               BIGINT      NOT NULL,
    provider              VARCHAR(20) NOT NULL,
    amount_htg            BIGINT      NOT NULL,
    credits               BIGINT      NOT NULL,
    status                VARCHAR(20) NOT NULL,
    provider_ref          VARCHAR(120),
    raw_payload           TEXT,
    note                  VARCHAR(255),
    confirmed_by_admin_id BIGINT,
    transaction_id        BIGINT,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    confirmed_at          DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_topup_provider_ref (provider, provider_ref),
    KEY ix_topup_user (user_id, created_at),
    KEY ix_topup_status (status),
    CONSTRAINT fk_topup_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_topup_amount_positive CHECK (amount_htg > 0 AND credits > 0)
) ENGINE = InnoDB;

-- Les gains sont debites (geles) des la demande, et re-credites si elle est
-- rejetee. Sans ce gel, trois demandes simultanees viderait trois fois le solde.
CREATE TABLE withdrawal_requests (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    user_id              BIGINT      NOT NULL,
    amount_credits       BIGINT      NOT NULL,
    amount_htg           BIGINT      NOT NULL,
    fee_htg              BIGINT      NOT NULL DEFAULT 0,
    net_htg              BIGINT      NOT NULL,
    provider             VARCHAR(20) NOT NULL,
    destination_number   VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    otp_verified_at      DATETIME(6),
    debit_transaction_id BIGINT,
    refund_transaction_id BIGINT,
    reviewed_by_admin_id BIGINT,
    reviewed_at          DATETIME(6),
    rejection_reason     VARCHAR(255),
    payout_reference     VARCHAR(120),
    paid_at              DATETIME(6),
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_withdrawal_user (user_id, created_at),
    KEY ix_withdrawal_status (status, created_at),
    CONSTRAINT fk_withdrawal_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_withdrawal_amount_positive CHECK (amount_credits > 0)
) ENGINE = InnoDB;

-- Le portefeuille de chaque compte existant. Les nouveaux comptes recoivent le
-- leur a l'inscription.
INSERT INTO wallets (user_id, credit_balance, winnings_balance, created_at, updated_at)
SELECT id, 0, 0, NOW(6), NOW(6) FROM users;

INSERT INTO app_settings (setting_key, setting_value, description, created_at, updated_at) VALUES
    ('withdrawal.min_credits', '200',
     'Montant minimum d un retrait, en credits', NOW(6), NOW(6)),
    ('withdrawal.max_credits_per_day', '20000',
     'Plafond de retrait par joueur et par jour, en credits', NOW(6), NOW(6)),
    ('withdrawal.fee_htg', '25',
     'Frais fixes preleves sur chaque retrait, en gourdes', NOW(6), NOW(6));
