package com.raikiservice.raf.quiz.web.dto;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.fraud.service.SoloAdjustment;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizReward;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.QuizTopic;
import com.raikiservice.raf.quiz.domain.TopicMode;

/**
 * Vue d'un quiz. Construite dans la transaction : toutes les relations sont paresseuses.
 *
 * <p>En SOLO, {@code ticketPriceCredits} est le prix d'une partie et
 * {@code soloPrizeHtg} le prix d'un sans-faute ; les champs propres au
 * groupe (seuil, recompenses, sujets soumis au vote) sont sans objet.
 */
public record QuizResponse(
        Long id,
        String title,
        String slug,
        String description,
        String themeLabel,
        String bannerUrl,
        /** Pour que le back-office garde la banniere en modifiant le quiz. */
        Long bannerMediaId,
        QuizStatus status,
        GameMode gameMode,
        TopicMode topicMode,
        Long selectedCategoryId,
        String selectedCategoryName,
        int ticketPriceCredits,
        long ticketPriceHtg,
        /** Ticket ou partie offerts (prix a 0). */
        boolean free,
        /** Quiz de test : visible du staff et des comptes de test seulement. */
        boolean test,
        /** Masque aux joueurs : hors des listes, ferme aux nouvelles entrees. */
        boolean hidden,
        int minPlayers,
        Integer maxTicketsTotal,
        Integer maxTicketsPerPlayer,
        PlayMode playMode,
        int questionDurationSeconds,
        int questionsPerGame,
        int pointsPerCorrect,
        Long soloPrizeHtg,
        /** Solo : fermeture automatique au premier sans-faute. */
        boolean closeOnPerfect,
        /** Solo : minutes pendant lesquelles le quiz est cache a un joueur apres son sans-faute ; null = desactive. */
        Integer perfectCooldownMinutes,
        /** Solo : part des questions faciles / moyennes / difficiles d'une partie ; null = tirage libre. */
        DifficultyMixDto difficultyMix,
        int minScoreThreshold,
        int closingCountdownMinutes,
        int startDelayMinutes,
        Instant voteOpensAt,
        Instant voteClosesAt,
        boolean voteOpen,
        Instant thresholdReachedAt,
        Instant scheduledStartAt,
        Instant startedAt,
        Instant endsAt,
        Instant finishedAt,
        String cancellationReason,
        long playersRegistered,
        long ticketsSold,
        long poolSize,
        List<RewardView> rewards,
        List<TopicView> topics,
        /**
         * Solo : anti-fraude pour tous les joueurs de ce quiz. Back-office seulement : null (donc absent
         * du JSON) dans l'app joueur, voir {@link #forPlayers()}.
         */
        Boolean antiFraud) {

    /** Montant par joueur de la tranche, en HTG. */
    public record RewardView(int rankFrom, int rankTo, long amountHtg,
            String label) {
    }

    public record TopicView(Long id, Long categoryId, String label, String imageUrl,
            int voteCount) {
    }

    public static QuizResponse from(Quiz quiz, List<QuizReward> rewards, List<QuizTopic> topics,
            long playersRegistered, long ticketsSold, long poolSize, long htgRate) {

        return new QuizResponse(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getSlug(),
                quiz.getDescription(),
                quiz.getThemeLabel(),
                quiz.getBanner() == null ? null : quiz.getBanner().getUrl(),
                quiz.getBanner() == null ? null : quiz.getBanner().getId(),
                quiz.getStatus(),
                quiz.getGameMode(),
                quiz.getTopicMode(),
                quiz.getSelectedCategory() == null ? null : quiz.getSelectedCategory().getId(),
                quiz.getSelectedCategory() == null ? null : quiz.getSelectedCategory().getName(),
                quiz.getTicketPriceCredits(),
                quiz.getTicketPriceCredits() * htgRate,
                quiz.isFree(),
                quiz.isTest(),
                quiz.isHidden(),
                quiz.getMinPlayers(),
                quiz.getMaxTicketsTotal(),
                quiz.getMaxTicketsPerPlayer(),
                quiz.getPlayMode(),
                quiz.getQuestionDurationSeconds(),
                quiz.getQuestionsPerGame(),
                quiz.getPointsPerCorrect(),
                quiz.getSoloPrizeHtg(),
                quiz.isCloseOnPerfect(),
                quiz.getPerfectCooldownMinutes(),
                DifficultyMixDto.from(quiz.getDifficultyMix()),
                quiz.getMinScoreThreshold(),
                quiz.getClosingCountdownMinutes(),
                quiz.getStartDelayMinutes(),
                quiz.getVoteOpensAt(),
                quiz.getVoteClosesAt(),
                quiz.isVoteOpen(Instant.now()),
                quiz.getThresholdReachedAt(),
                quiz.getScheduledStartAt(),
                quiz.getStartedAt(),
                quiz.getEndsAt(),
                quiz.getFinishedAt(),
                quiz.getCancellationReason(),
                playersRegistered,
                ticketsSold,
                poolSize,
                rewards.stream()
                        .map(reward -> new RewardView(reward.getRankFrom(), reward.getRankTo(),
                                reward.getAmountHtg(),
                                reward.getLabel()))
                        .toList(),
                topics.stream()
                        .map(topic -> new TopicView(topic.getId(), topic.getCategory().getId(),
                                topic.getLabel(),
                                topic.getMedia() == null ? null : topic.getMedia().getUrl(),
                                topic.getVoteCount()))
                        .toList(),
                quiz.getGameMode() == GameMode.SOLO ? quiz.isAntiFraud() : null);
    }

    /**
     * Vue de l'app joueur : sans le reglage anti-fraude, qui ne doit pas se voir. Les
     * nulls etant omis du JSON, le champ disparait.
     */
    public QuizResponse forPlayers() {
        if (antiFraud == null) {
            return this;
        }
        return new QuizResponse(id, title, slug, description, themeLabel, bannerUrl, bannerMediaId, status,
                gameMode, topicMode, selectedCategoryId, selectedCategoryName, ticketPriceCredits, ticketPriceHtg,
                free, test, hidden, minPlayers, maxTicketsTotal, maxTicketsPerPlayer, playMode,
                questionDurationSeconds, questionsPerGame, pointsPerCorrect, soloPrizeHtg, closeOnPerfect,
                perfectCooldownMinutes, difficultyMix, minScoreThreshold, closingCountdownMinutes,
                startDelayMinutes, voteOpensAt, voteClosesAt, voteOpen, thresholdReachedAt, scheduledStartAt,
                startedAt, endsAt, finishedAt, cancellationReason, playersRegistered, ticketsSold, poolSize,
                rewards, topics, null);
    }

    /**
     * Le quiz tel qu'un joueur suspecte de fraude le joue en solo : prix, secondes par
     * question et nombre de questions de son profil (FraudProfileService). Ce sont les
     * valeurs encaissees et jouees ; le gain du sans-faute ne change pas.
     */
    public QuizResponse adjustedFor(SoloAdjustment adjustment) {
        if (gameMode != GameMode.SOLO) {
            return this;
        }
        int price = adjustment.price(ticketPriceCredits);
        return new QuizResponse(
                id,
                title,
                slug,
                description,
                themeLabel,
                bannerUrl,
                bannerMediaId,
                status,
                gameMode,
                topicMode,
                selectedCategoryId,
                selectedCategoryName,
                price,
                ticketPriceCredits == 0 ? 0 : ticketPriceHtg / ticketPriceCredits * price,
                free,
                test,
                hidden,
                minPlayers,
                maxTicketsTotal,
                maxTicketsPerPlayer,
                playMode,
                adjustment.questionSeconds(questionDurationSeconds),
                adjustment.questions(questionsPerGame),
                pointsPerCorrect,
                soloPrizeHtg,
                closeOnPerfect,
                perfectCooldownMinutes,
                difficultyMix,
                minScoreThreshold,
                closingCountdownMinutes,
                startDelayMinutes,
                voteOpensAt,
                voteClosesAt,
                voteOpen,
                thresholdReachedAt,
                scheduledStartAt,
                startedAt,
                endsAt,
                finishedAt,
                cancellationReason,
                playersRegistered,
                ticketsSold,
                poolSize,
                rewards,
                topics,
                antiFraud);
    }
}
