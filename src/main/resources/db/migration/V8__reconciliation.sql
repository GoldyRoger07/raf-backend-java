-- =============================================================================
-- RAF - Rapprochement comptable
--
-- Chaque execution compare les soldes au journal des transactions, et les
-- operations metier (tickets, recharges, retraits, gains) aux mouvements
-- d'argent qui doivent les accompagner. Les ecarts sont conserves pour enquete.
-- =============================================================================

CREATE TABLE reconciliation_runs (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    trigger_type           VARCHAR(20)  NOT NULL,
    triggered_by           BIGINT,
    status                 VARCHAR(20)  NOT NULL,
    started_at             DATETIME(6)  NOT NULL,
    finished_at            DATETIME(6),
    wallets_checked        BIGINT       NOT NULL DEFAULT 0,
    transactions_checked   BIGINT       NOT NULL DEFAULT 0,
    finding_count          INT          NOT NULL DEFAULT 0,
    total_credit_balance   BIGINT       NOT NULL DEFAULT 0,
    total_winnings_balance BIGINT       NOT NULL DEFAULT 0,
    error_message          VARCHAR(1000),
    PRIMARY KEY (id),
    KEY ix_reconciliation_runs_started (started_at)
) ENGINE = InnoDB;

-- Au-dela de 500 ecarts par execution, seul le compte est garde : le detail
-- d'une incoherence massive n'apporte rien de plus que ses 500 premiers cas.
CREATE TABLE reconciliation_findings (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    run_id         BIGINT       NOT NULL,
    check_code     VARCHAR(40)  NOT NULL,
    user_id        BIGINT,
    wallet_id      BIGINT,
    reference_type VARCHAR(40),
    reference_id   BIGINT,
    expected_value BIGINT,
    actual_value   BIGINT,
    detail         VARCHAR(500),
    PRIMARY KEY (id),
    KEY ix_reconciliation_findings_run (run_id, check_code),
    CONSTRAINT fk_reconciliation_findings_run FOREIGN KEY (run_id)
        REFERENCES reconciliation_runs (id) ON DELETE CASCADE
) ENGINE = InnoDB;

-- La recherche de mouvements orphelins remonte de chaque transaction vers
-- l'operation qui doit la referencer : sans ces index, chaque transaction
-- declencherait un parcours complet de la table metier.
CREATE INDEX ix_tickets_transaction ON tickets (transaction_id);
CREATE INDEX ix_tickets_refund_transaction ON tickets (refund_transaction_id);
CREATE INDEX ix_topup_transaction ON topup_requests (transaction_id);
CREATE INDEX ix_withdrawal_debit_transaction ON withdrawal_requests (debit_transaction_id);
CREATE INDEX ix_withdrawal_refund_transaction ON withdrawal_requests (refund_transaction_id);
CREATE INDEX ix_quiz_rankings_transaction ON quiz_rankings (transaction_id);
