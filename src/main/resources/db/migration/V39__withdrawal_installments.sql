-- Retraits verses par tranches, et accord du joueur pour ce mode de paiement.
--
-- withdrawal_payouts : chaque tranche versee a la main (montant net, reference de
-- l'operateur). paid_htg en fait la somme ; un retrait passe en PARTIALLY_PAID apres
-- la premiere tranche, en PAID quand paid_htg atteint net_htg. Les frais ne sont
-- preleves qu'une fois, a la demande.
--
-- refunded_htg : reste non verse rendu aux gains quand l'admin clot un retrait paye en
-- partie (statut PAID, refund_transaction_id renseigne). paid_htg + refunded_htg = net_htg
-- pour tout retrait PAID.
ALTER TABLE withdrawal_requests
    ADD COLUMN paid_htg     BIGINT NOT NULL DEFAULT 0 AFTER net_htg,
    ADD COLUMN refunded_htg BIGINT NOT NULL DEFAULT 0 AFTER paid_htg;

UPDATE withdrawal_requests SET paid_htg = net_htg WHERE status = 'PAID';

CREATE TABLE withdrawal_payouts (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    withdrawal_id      BIGINT       NOT NULL,
    amount_htg         BIGINT       NOT NULL,
    payout_reference   VARCHAR(191) NOT NULL,
    admin_id           BIGINT,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_withdrawal_payouts_withdrawal (withdrawal_id, id),
    CONSTRAINT fk_withdrawal_payouts_withdrawal FOREIGN KEY (withdrawal_id)
        REFERENCES withdrawal_requests (id)
) ENGINE = InnoDB;

-- Accord « RAF peut me payer en plusieurs fois », demande avant le solo a tout joueur
-- qui a deja fait un sans-faute. La version permet de le redemander si le texte change.
ALTER TABLE users
    ADD COLUMN payout_terms_version     INT         NULL,
    ADD COLUMN payout_terms_accepted_at DATETIME(6) NULL;
