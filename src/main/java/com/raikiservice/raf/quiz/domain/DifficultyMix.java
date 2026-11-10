package com.raikiservice.raf.quiz.domain;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.question.domain.Difficulty;

/**
 * Quiz solo : part des questions faciles, moyennes et difficiles dans chaque partie,
 * en pourcentages entiers dont le total vaut 100. Un niveau peut etre a 0.
 */
public record DifficultyMix(int easyPercent, int mediumPercent, int hardPercent) {

    /** Plus difficile d'abord : les questions en trop vont a ce niveau en cas d'egalite. */
    private static final List<Difficulty> HARDEST_FIRST =
            List.of(Difficulty.HARD, Difficulty.MEDIUM, Difficulty.EASY);

    public DifficultyMix {
        if (easyPercent < 0 || mediumPercent < 0 || hardPercent < 0
                || easyPercent + mediumPercent + hardPercent != 100) {
            throw new IllegalArgumentException("Repartition invalide : " + easyPercent + "/"
                    + mediumPercent + "/" + hardPercent);
        }
    }

    public int percent(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> easyPercent;
            case MEDIUM -> mediumPercent;
            case HARD -> hardPercent;
        };
    }

    /**
     * Nombre de questions par niveau pour une partie de {@code questions} questions.
     * Chaque niveau recoit la partie entiere de sa part ; les questions restantes vont
     * aux plus fortes decimales, a egalite au plus difficile. 33/33/34 % sur 10 donne
     * 3/3/4. Un niveau a 0 % ne recoit jamais rien.
     */
    public Map<Difficulty, Integer> split(int questions) {
        Map<Difficulty, Integer> counts = new EnumMap<>(Difficulty.class);
        Map<Difficulty, Integer> remainders = new EnumMap<>(Difficulty.class);
        int assigned = 0;
        for (Difficulty level : Difficulty.values()) {
            int share = questions * percent(level);
            counts.put(level, share / 100);
            remainders.put(level, share % 100);
            assigned += share / 100;
        }
        List<Difficulty> byRemainder = HARDEST_FIRST.stream()
                .sorted(Comparator.comparing(remainders::get, Comparator.reverseOrder()))
                .toList();
        for (int i = 0; assigned < questions; i++, assigned++) {
            counts.merge(byRemainder.get(i), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Niveaux qui completent une partie quand un niveau manque de questions, dans
     * l'ordre : le niveau voisin d'abord. Un niveau moyen se complete par le difficile
     * avant le facile, pour ne pas rendre le sans-faute plus facile qu'annonce.
     */
    public static List<Difficulty> fallbacks(Difficulty level) {
        return switch (level) {
            case EASY -> List.of(Difficulty.MEDIUM, Difficulty.HARD);
            case MEDIUM -> List.of(Difficulty.HARD, Difficulty.EASY);
            case HARD -> List.of(Difficulty.MEDIUM, Difficulty.EASY);
        };
    }

    /**
     * Repartition effective d'une partie quand le vivier compte {@code available}
     * questions par niveau : chaque niveau prend ce qu'il peut, le manque passe aux
     * niveaux de repli ({@link #fallbacks}). Le total peut rester sous l'objectif si
     * le vivier entier est trop petit.
     */
    public static Map<Difficulty, Integer> allocate(Map<Difficulty, Integer> targets,
            Map<Difficulty, Integer> available) {
        Map<Difficulty, Integer> taken = new EnumMap<>(Difficulty.class);
        Map<Difficulty, Integer> missing = new EnumMap<>(Difficulty.class);
        for (Difficulty level : Difficulty.values()) {
            int target = targets.getOrDefault(level, 0);
            int take = Math.min(target, available.getOrDefault(level, 0));
            taken.put(level, take);
            missing.put(level, target - take);
        }
        for (Difficulty level : Difficulty.values()) {
            for (Difficulty fallback : fallbacks(level)) {
                int spare = available.getOrDefault(fallback, 0) - taken.get(fallback);
                int take = Math.min(missing.get(level), spare);
                if (take > 0) {
                    taken.merge(fallback, take, Integer::sum);
                    missing.merge(level, -take, Integer::sum);
                }
            }
        }
        return taken;
    }
}
