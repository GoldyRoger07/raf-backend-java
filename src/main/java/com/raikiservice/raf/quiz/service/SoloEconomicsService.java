package com.raikiservice.raf.quiz.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.quiz.domain.DifficultyMix;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizQuestionPoolRepository;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bilan previsionnel d'un quiz solo.
 *
 * <p>Chaque partie rapporte son prix d'entree, chaque sans-faute coute le prix
 * gagne : tout depend donc de la probabilite d'un sans-faute. Elle est estimee a
 * partir du taux de reussite reel de chaque question du vivier. Un joueur tire
 * N questions au hasard : sa chance de toutes les reussir vaut la reussite
 * moyenne du vivier elevee a la puissance N. Avec une repartition par difficulte,
 * chaque niveau compte pour son nombre de questions : facile^n1 x moyen^n2 x
 * difficile^n3, la reussite de chaque niveau etant celle de ses questions.
 *
 * <p>Une question peu servie n'a pas de taux fiable : son taux observe est
 * tire vers un taux a priori selon sa difficulte declaree, d'autant plus que
 * l'echantillon est petit.
 *
 * <p>Limites : les compteurs melangent les parties de groupe et solo, tous
 * chronos confondus, et ignorent qu'un bon joueur reussit mieux partout (les
 * reponses d'un meme joueur ne sont pas independantes). L'estimation sert a
 * calibrer avant l'ouverture ; une fois le quiz ouvert, le taux observe fait foi.
 */
@Service
@RequiredArgsConstructor
public class SoloEconomicsService {

    /** Poids de l'a priori, en reponses fictives. */
    private static final int PRIOR_WEIGHT = 10;

    /** En dessous, une question compte comme "sans historique". */
    private static final int MIN_SERVED_FOR_STATS = PRIOR_WEIGHT;

    private final QuestionRepository questionRepository;
    private final QuizQuestionPoolRepository quizQuestionPoolRepository;
    private final GameSessionRepository gameSessionRepository;
    private final WalletService walletService;

    /**
     * Les taux sont des fractions (0,05 pour 5 %).
     *
     * @param breakEvenWinRate taux de sans-faute au-dela duquel le quiz perd de
     *        l'argent ; 0 pour un quiz gratuit
     * @param breakEvenPrizeHtg prix le plus eleve que le quiz supporte sans
     *        perte, au taux de sans-faute estime
     * @param estimatedAccuracy reussite par question equivalente : la racine N-ieme
     *        du taux de sans-faute (la moyenne du vivier en tirage libre)
     * @param gamesPlayed parties jouees (null tant que le quiz est en brouillon)
     * @param byDifficulty detail par niveau quand une repartition est fixee, vide sinon
     */
    public record SoloEconomics(
            int entryPriceCredits,
            long entryPriceHtg,
            long prizeHtg,
            int questionsPerGame,
            double breakEvenWinRate,
            int poolQuestions,
            int questionsWithStats,
            double estimatedAccuracy,
            double estimatedWinRate,
            long expectedMarginPerGameHtg,
            long breakEvenPrizeHtg,
            boolean profitable,
            Long gamesPlayed,
            Long gamesWon,
            Double observedWinRate,
            Long observedMarginHtg,
            List<LevelEstimate> byDifficulty) {
    }

    /**
     * Un niveau de la repartition.
     *
     * @param targetPerGame questions voulues par partie (pourcentage applique)
     * @param servedPerGame questions effectivement servies, une fois complete par les
     *        niveaux voisins si le vivier en manque
     * @param accuracy reussite moyenne des questions de ce niveau ; null s'il n'y en a pas
     */
    public record LevelEstimate(
            Difficulty difficulty,
            int percent,
            int targetPerGame,
            int servedPerGame,
            int poolQuestions,
            Double accuracy) {
    }

    /** Simulation pour le formulaire : rien n'est enregistre. */
    @Transactional(readOnly = true)
    public SoloEconomics preview(Long categoryId, int entryPriceCredits, long prizeHtg,
            int questionsPerGame, DifficultyMix mix) {
        if (categoryId == null || entryPriceCredits < 0 || prizeHtg < 1 || questionsPerGame < 1) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        return estimate(entryPriceCredits, prizeHtg, questionsPerGame, mix,
                questionRepository.usageStats(categoryId, QuestionStatus.PUBLISHED), null, null);
    }

    /**
     * En brouillon, le vivier est celui que figerait l'ouverture ; ensuite, c'est
     * le vivier fige, et les parties jouees donnent le taux observe.
     */
    @Transactional(readOnly = true)
    public SoloEconomics forQuiz(Quiz quiz) {
        if (!quiz.isSolo()) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE, "Action reservee aux quiz solo.");
        }
        long prize = quiz.getSoloPrizeHtg() == null ? 0 : quiz.getSoloPrizeHtg();

        if (quiz.getStatus() == QuizStatus.DRAFT) {
            List<QuestionRepository.UsageStats> stats = quiz.getSelectedCategory() == null
                    ? List.of()
                    : questionRepository.usageStats(quiz.getSelectedCategory().getId(),
                            QuestionStatus.PUBLISHED);
            return estimate(quiz.getTicketPriceCredits(), prize, quiz.getQuestionsPerGame(),
                    quiz.getDifficultyMix(), stats, null, null);
        }

        return estimate(quiz.getTicketPriceCredits(), prize, quiz.getQuestionsPerGame(),
                quiz.getDifficultyMix(), quizQuestionPoolRepository.usageStats(quiz.getId()),
                gameSessionRepository.countByQuizIdAndStatusNotAndTestFalse(quiz.getId(),
                        GameSessionStatus.IN_PROGRESS),
                gameSessionRepository.countByQuizIdAndWonTrue(quiz.getId()));
    }

    private SoloEconomics estimate(int entryPriceCredits, long prizeHtg, int questionsPerGame,
            DifficultyMix mix, List<QuestionRepository.UsageStats> pool, Long gamesPlayed,
            Long gamesWon) {

        long entry = entryPriceCredits * walletService.htgRate();

        int withStats = 0;
        double sum = 0;
        Map<Difficulty, Double> levelSum = new EnumMap<>(Difficulty.class);
        Map<Difficulty, Integer> levelCount = new EnumMap<>(Difficulty.class);
        for (QuestionRepository.UsageStats question : pool) {
            if (question.getTimesServed() >= MIN_SERVED_FOR_STATS) {
                withStats++;
            }
            Difficulty level = question.getDifficulty() == null ? Difficulty.MEDIUM : question.getDifficulty();
            double smoothed = (question.getTimesCorrect() + PRIOR_WEIGHT * prior(level))
                    / (question.getTimesServed() + PRIOR_WEIGHT);
            sum += smoothed;
            levelSum.merge(level, smoothed, Double::sum);
            levelCount.merge(level, 1, Integer::sum);
        }
        double poolAccuracy = pool.isEmpty() ? prior(Difficulty.MEDIUM) : sum / pool.size();

        double accuracy;
        double winRate;
        List<LevelEstimate> levels = new ArrayList<>();
        if (mix == null) {
            accuracy = poolAccuracy;
            winRate = Math.pow(accuracy, questionsPerGame);
        } else {
            // Meme repartition que le tirage : un niveau trop petit est complete par
            // ses voisins. Seul un vivier entier trop petit laisse des questions sans
            // niveau : elles comptent a la reussite moyenne du vivier.
            Map<Difficulty, Integer> targets = mix.split(questionsPerGame);
            Map<Difficulty, Integer> served = DifficultyMix.allocate(targets, levelCount);
            winRate = 1;
            int counted = 0;
            for (Difficulty level : Difficulty.values()) {
                int count = levelCount.getOrDefault(level, 0);
                Double levelAccuracy = count == 0 ? null : levelSum.get(level) / count;
                int n = served.get(level);
                if (n > 0) {
                    winRate *= Math.pow(levelAccuracy, n);
                    counted += n;
                }
                levels.add(new LevelEstimate(level, mix.percent(level), targets.get(level), n, count,
                        levelAccuracy));
            }
            winRate *= Math.pow(poolAccuracy, questionsPerGame - counted);
            accuracy = Math.pow(winRate, 1.0 / questionsPerGame);
        }

        Double observedWinRate = null;
        Long observedMargin = null;
        if (gamesPlayed != null && gamesPlayed > 0) {
            observedWinRate = (double) gamesWon / gamesPlayed;
            observedMargin = gamesPlayed * entry - gamesWon * prizeHtg;
        }

        long expectedMargin = Math.round(entry - winRate * prizeHtg);
        return new SoloEconomics(
                entryPriceCredits,
                entry,
                prizeHtg,
                questionsPerGame,
                prizeHtg > 0 ? (double) entry / prizeHtg : 0,
                pool.size(),
                withStats,
                accuracy,
                winRate,
                expectedMargin,
                winRate > 0 ? (long) Math.floor(entry / winRate) : 0,
                expectedMargin > 0,
                gamesPlayed,
                gamesWon,
                observedWinRate,
                observedMargin,
                levels);
    }

    /** Reussite attendue d'une question jamais servie, selon sa difficulte declaree. */
    private static double prior(Difficulty difficulty) {
        return switch (difficulty == null ? Difficulty.MEDIUM : difficulty) {
            case EASY -> 0.85;
            case MEDIUM -> 0.70;
            case HARD -> 0.55;
        };
    }
}
