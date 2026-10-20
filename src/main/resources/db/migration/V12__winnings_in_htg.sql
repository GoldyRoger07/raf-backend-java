-- =============================================================================
-- RAF - Les gains en gourdes
--
-- Le solde de gains est une dette reelle de RAF envers les joueurs, retirable en
-- HTG : il est desormais tenu en gourdes (entiers), comme tout ce qui l'alimente
-- (recompenses par rang, prix d'un sans-faute) ou le vide (retraits). Changer la
-- valeur du credit ne reevalue plus ce que RAF doit.
--
-- Restent en credits : le solde de jeu (credit_balance), le prix des tickets et
-- celui des parties solo. Le credit ne sert plus qu'a acheter du jeu.
--
-- Conversion des donnees existantes au taux en vigueur (credit.htg_rate).
-- =============================================================================

SET @rate = COALESCE(
    (SELECT CAST(setting_value AS UNSIGNED) FROM app_settings WHERE setting_key = 'credit.htg_rate'),
    5);

-- Solde de gains et son grand livre. Les deux sont convertis ensemble : la somme
-- des mouvements reste egale au solde, et l'enchainement des soldes intact.
UPDATE wallets SET winnings_balance = winnings_balance * @rate;

UPDATE wallet_transactions
SET amount = amount * @rate,
    balance_after = balance_after * @rate
WHERE balance_type = 'WINNINGS';

-- Recompenses par rang (annoncees aux joueurs en HTG).
ALTER TABLE quiz_rewards DROP CHECK ck_quiz_rewards_amount;
ALTER TABLE quiz_rewards RENAME COLUMN amount_credits TO amount_htg;
UPDATE quiz_rewards SET amount_htg = amount_htg * @rate;
ALTER TABLE quiz_rewards ADD CONSTRAINT ck_quiz_rewards_amount CHECK (amount_htg > 0);

ALTER TABLE quiz_rankings RENAME COLUMN reward_credits TO reward_htg;
UPDATE quiz_rankings SET reward_htg = reward_htg * @rate;

-- Prix d'un sans-faute en solo, sur le quiz et copie dans chaque partie.
ALTER TABLE quizzes RENAME COLUMN solo_prize_credits TO solo_prize_htg;
UPDATE quizzes SET solo_prize_htg = solo_prize_htg * @rate WHERE solo_prize_htg IS NOT NULL;

ALTER TABLE game_sessions RENAME COLUMN prize_credits TO prize_htg;
UPDATE game_sessions SET prize_htg = prize_htg * @rate WHERE prize_htg IS NOT NULL;

-- Retraits : amount_htg (deja fige a la demande) devient le montant debite des
-- gains ; le montant en credits n'a plus de sens.
ALTER TABLE withdrawal_requests DROP CHECK ck_withdrawal_amount_positive;
ALTER TABLE withdrawal_requests DROP COLUMN amount_credits;
ALTER TABLE withdrawal_requests ADD CONSTRAINT ck_withdrawal_amount_positive CHECK (amount_htg > 0);

-- Historique du rapprochement : total des gains en circulation, en HTG.
UPDATE reconciliation_runs SET total_winnings_balance = total_winnings_balance * @rate;

-- Limites de retrait, en HTG.
UPDATE app_settings
SET setting_key = 'withdrawal.min_htg',
    setting_value = CAST(CAST(setting_value AS UNSIGNED) * @rate AS CHAR),
    description = 'Montant minimum d un retrait, en gourdes'
WHERE setting_key = 'withdrawal.min_credits';

UPDATE app_settings
SET setting_key = 'withdrawal.max_htg_per_day',
    setting_value = CAST(CAST(setting_value AS UNSIGNED) * @rate AS CHAR),
    description = 'Plafond de retrait par joueur et par jour, en gourdes'
WHERE setting_key = 'withdrawal.max_credits_per_day';
