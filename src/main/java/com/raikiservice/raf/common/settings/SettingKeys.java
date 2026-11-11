package com.raikiservice.raf.common.settings;

import java.util.Map;

public final class SettingKeys {

    public static final String CREDIT_HTG_RATE = "credit.htg_rate";
    public static final String WITHDRAWAL_MIN_HTG = "withdrawal.min_htg";
    public static final String WITHDRAWAL_MAX_HTG_PER_DAY = "withdrawal.max_htg_per_day";
    public static final String WITHDRAWAL_FEE_HTG = "withdrawal.fee_htg";
    public static final String WITHDRAWAL_COUNTER_FEE_HTG = "withdrawal.counter_fee_htg";
    /** Retrait automatique (payout Tranzak) : frais de la passerelle retenus au joueur, en points de base. */
    public static final String WITHDRAWAL_PAYOUT_FEE_BPS = "withdrawal.payout_fee_bps";
    public static final String GAME_POOL_RATIO_MIN = "game.pool_ratio_min";
    public static final String GAME_NETWORK_GRACE_SECONDS = "game.network_grace_seconds";
    public static final String GAME_QUESTION_GRACE_MS = "game.question_grace_ms";
    public static final String GAME_MIN_ANSWER_MS = "game.min_answer_ms";
    public static final String GAME_FAST_ANSWER_FLAG_COUNT = "game.fast_answer_flag_count";
    public static final String QUESTION_IMPORT_MAX_ROWS = "question.import_max_rows";
    public static final String AI_MAX_QUESTIONS_PER_REQUEST = "ai.max_questions_per_request";
    public static final String AI_MAX_REQUESTS_PER_DAY = "ai.max_requests_per_day";
    /** Recharge en ligne (Tranzak) : frais factures au joueur en points de base, bornes en HTG. */
    public static final String TOPUP_ONLINE_FEE_BPS = "topup.online_fee_bps";
    public static final String TOPUP_ONLINE_MIN_HTG = "topup.online_min_htg";
    public static final String TOPUP_ONLINE_MAX_HTG = "topup.online_max_htg";
    /** Bornes d'une recharge d'un joueur par son leader, en credits. */
    public static final String LEADER_TRANSFER_MIN_CREDITS = "leader.transfer_min_credits";
    public static final String LEADER_TRANSFER_MAX_CREDITS = "leader.transfer_max_credits";

    /** Reseaux sociaux : ni des entiers ni reserves au super-administrateur, voir SocialService. */
    public static final String INSTAGRAM_ENABLED = "social.instagram.enabled";
    public static final String INSTAGRAM_URL = "social.instagram.url";
    private static final String SOCIAL_PREFIX = "social.";

    /** Numeros ou les joueurs envoient leurs recharges a distance, voir DepositNumbersService. */
    public static final String DEPOSIT_MONCASH_ENABLED = "deposit.moncash.enabled";
    public static final String DEPOSIT_MONCASH_NUMBER = "deposit.moncash.number";
    public static final String DEPOSIT_NATCASH_ENABLED = "deposit.natcash.enabled";
    public static final String DEPOSIT_NATCASH_NUMBER = "deposit.natcash.number";
    private static final String DEPOSIT_PREFIX = "deposit.";

    /** Comportement de l'app joueur, voir PlayerAppService. */
    public static final String APP_EMAIL_REMINDER_ENABLED = "app.email_reminder.enabled";
    /** Recharge des joueurs par leur leader, voir LeaderTransferService. */
    public static final String APP_LEADER_TRANSFER_ENABLED = "app.leader_transfer.enabled";
    /** Conversion des gains (HTG) en credits par le joueur, voir TopUpService#convertWinnings. */
    public static final String APP_WINNINGS_CONVERSION_ENABLED = "app.winnings_conversion.enabled";
    /** Recharge en ligne MonCash / NatCash par la passerelle, voir OnlineTopUpService. */
    public static final String APP_ONLINE_TOPUP_ENABLED = "app.online_topup.enabled";
    /** Envoi automatique des retraits MonCash / NatCash a l'approbation, voir AutoPayoutService. */
    public static final String APP_AUTO_PAYOUT_ENABLED = "app.auto_payout.enabled";
    /** Interrupteur global des quiz solo ouverts : coupe, ils sont tous masques, voir QuizAccess. */
    public static final String APP_SOLO_QUIZZES_VISIBLE = "app.solo_quizzes.visible";
    /** Premiere partie solo offerte, une fois par compte ; coupee par defaut, voir FirstSoloOfferService. */
    public static final String APP_FIRST_SOLO_FREE_ENABLED = "app.first_solo_free.enabled";
    private static final String APP_PREFIX = "app.";

    /** Parametre gere par son propre ecran, hors de la liste des parametres entiers. */
    public static boolean hasOwnScreen(String key) {
        return key.startsWith(SOCIAL_PREFIX) || key.startsWith(DEPOSIT_PREFIX)
                || key.startsWith(APP_PREFIX);
    }

    /**
     * Valeur minimale acceptee depuis le back-office. Tous les parametres sont des
     * entiers ; ceux qui servent de diviseur ou de seuil d'existence (valeur du
     * credit, ratio du vivier, plafonds) ne peuvent pas descendre a zero.
     */
    private static final Map<String, Long> MIN_VALUES = Map.ofEntries(
            Map.entry(CREDIT_HTG_RATE, 1L),
            Map.entry(WITHDRAWAL_MIN_HTG, 1L),
            Map.entry(WITHDRAWAL_MAX_HTG_PER_DAY, 1L),
            Map.entry(GAME_POOL_RATIO_MIN, 1L),
            Map.entry(GAME_FAST_ANSWER_FLAG_COUNT, 1L),
            Map.entry(QUESTION_IMPORT_MAX_ROWS, 1L),
            Map.entry(AI_MAX_QUESTIONS_PER_REQUEST, 1L),
            Map.entry(LEADER_TRANSFER_MIN_CREDITS, 1L),
            Map.entry(LEADER_TRANSFER_MAX_CREDITS, 1L),
            Map.entry(TOPUP_ONLINE_MIN_HTG, 1L),
            Map.entry(TOPUP_ONLINE_MAX_HTG, 1L));

    public static long minValue(String key) {
        return MIN_VALUES.getOrDefault(key, 0L);
    }

    private SettingKeys() {
    }
}
