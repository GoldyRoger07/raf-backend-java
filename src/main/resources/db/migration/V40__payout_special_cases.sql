-- Cas de retrait particuliers : joueurs dont les retraits peuvent etre verses par
-- tranches. Seuls eux voient l'accord « RAF peut me payer en plusieurs fois » avant le
-- solo (V39, users.payout_terms_*), et seuls leurs retraits se fractionnent. Retirer un
-- joueur de la liste garde son accord : remis plus tard, il n'a pas a reaccepter.
CREATE TABLE payout_special_cases (
    user_id     BIGINT       NOT NULL,
    note        VARCHAR(255),
    added_by    BIGINT,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_payout_special_cases_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB;
