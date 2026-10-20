package com.raikiservice.raf.common.settings;

import java.util.Map;

public final class SettingKeys {

    public static final String CREDIT_HTG_RATE = "credit.htg_rate";
    public static final String WITHDRAWAL_MIN_HTG = "withdrawal.min_htg";
    public static final String WITHDRAWAL_MAX_HTG_PER_DAY = "withdrawal.max_htg_per_day";
    public static final String WITHDRAWAL_FEE_HTG = "withdrawal.fee_htg";
    public static final String WITHDRAWAL_COUNTER_FEE_HTG = "withdrawal.counter_fee_htg";
    public static final String GAME_POOL_RATIO_MIN = "game.pool_ratio_min";
    public static final String GAME_NETWORK_GRACE_SECONDS = "game.network_grace_seconds";
    public static final String GAME_QUESTION_GRACE_MS = "game.question_grace_ms";
    public static final String GAME_MIN_ANSWER_MS = "game.min_answer_ms";
    public static final String GAME_FAST_ANSWER_FLAG_COUNT = "game.fast_answer_flag_count";
    public static final String QUESTION_IMPORT_MAX_ROWS = "question.import_max_rows";
    public static final String AI_MAX_QUESTIONS_PER_REQUEST = "ai.max_questions_per_request";
    public static final String AI_MAX_REQUESTS_PER_DAY = "ai.max_requests_per_day";

    /**
     * Valeur minimale acceptee depuis le back-office. Tous les parametres sont des
     * entiers ; ceux qui servent de diviseur ou de seuil d'existence (valeur du
     * credit, ratio du vivier, plafonds) ne peuvent pas descendre a zero.
     */
    private static final Map<String, Long> MIN_VALUES = Map.of(
            CREDIT_HTG_RATE, 1L,
            WITHDRAWAL_MIN_HTG, 1L,
            WITHDRAWAL_MAX_HTG_PER_DAY, 1L,
            GAME_POOL_RATIO_MIN, 1L,
            GAME_FAST_ANSWER_FLAG_COUNT, 1L,
            QUESTION_IMPORT_MAX_ROWS, 1L,
            AI_MAX_QUESTIONS_PER_REQUEST, 1L);

    public static long minValue(String key) {
        return MIN_VALUES.getOrDefault(key, 0L);
    }

    private SettingKeys() {
    }
}
