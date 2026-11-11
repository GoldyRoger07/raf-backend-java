package com.raikiservice.raf.fraud.service;

/**
 * Durcissement d'une partie solo pour un joueur suspecte de fraude, en pourcentages.
 *
 * <p>Arrondis toujours contre le joueur : prix au credit superieur, secondes a l'entier
 * inferieur (au moins 1), questions a l'entier superieur. Un quiz gratuit reste gratuit.
 */
public record SoloAdjustment(int priceIncreasePercent, int durationDecreasePercent,
        int questionsIncreasePercent) {

    public static final int MAX_PRICE_INCREASE = 200;
    public static final int MAX_DURATION_DECREASE = 80;
    public static final int MAX_QUESTIONS_INCREASE = 100;

    /** Valeur par valeur, la plus severe des deux. */
    public SoloAdjustment harshest(SoloAdjustment other) {
        return new SoloAdjustment(
                Math.max(priceIncreasePercent, other.priceIncreasePercent),
                Math.max(durationDecreasePercent, other.durationDecreasePercent),
                Math.max(questionsIncreasePercent, other.questionsIncreasePercent));
    }

    public int price(int credits) {
        return ceilPercent(credits, 100 + priceIncreasePercent);
    }

    public int questionSeconds(int seconds) {
        return Math.max(1, seconds * (100 - durationDecreasePercent) / 100);
    }

    public int questions(int count) {
        return ceilPercent(count, 100 + questionsIncreasePercent);
    }

    private static int ceilPercent(int value, int percent) {
        return (int) ((value * (long) percent + 99) / 100);
    }
}
