-- Retenue de recharge : la part d'une recharge en gourdes qui ne forme pas un
-- credit entier est gardee pour le joueur et ajoutee a sa recharge suivante.
-- Exemple a 10 HTG le credit : 15 HTG donnent 1 credit et 5 HTG de retenue ;
-- 17 HTG la fois suivante font 22 HTG, soit 2 credits et 2 HTG de retenue.
--
-- Une ligne par mouvement de la retenue d'un joueur, comme un petit grand livre :
-- son solde est le balance_after_htg de sa ligne la plus recente.
CREATE TABLE topup_retentions (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    -- Positif : gourdes mises de cote ; negatif : gourdes reutilisees ou retirees.
    amount_htg        BIGINT       NOT NULL,
    balance_after_htg BIGINT       NOT NULL,
    -- KEPT (reste d'une recharge), USED (reutilisee par une recharge), ADJUSTMENT (correction).
    reason            VARCHAR(20)  NOT NULL,
    topup_request_id  BIGINT,
    admin_id          BIGINT,
    note              VARCHAR(255),
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_topup_retentions_user (user_id, id),
    KEY ix_topup_retentions_topup (topup_request_id),
    CONSTRAINT fk_topup_retentions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_topup_retentions_topup FOREIGN KEY (topup_request_id) REFERENCES topup_requests (id),
    CONSTRAINT ck_topup_retentions_amount CHECK (amount_htg <> 0),
    CONSTRAINT ck_topup_retentions_balance CHECK (balance_after_htg >= 0)
) ENGINE = InnoDB;

-- Detail de la conversion, fige a la confirmation :
-- credits * htg_rate = amount_htg + retention_used_htg - retention_kept_htg.
ALTER TABLE topup_requests
    ADD COLUMN htg_rate           BIGINT,
    ADD COLUMN retention_used_htg BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN retention_kept_htg BIGINT NOT NULL DEFAULT 0;

-- Une recharge trop petite pour un credit est acceptee : tout part en retenue.
ALTER TABLE topup_requests DROP CHECK ck_topup_amount_positive;
ALTER TABLE topup_requests
    ADD CONSTRAINT ck_topup_amount_positive CHECK (amount_htg > 0 AND credits >= 0),
    ADD CONSTRAINT ck_topup_retention CHECK (retention_used_htg >= 0 AND retention_kept_htg >= 0);
