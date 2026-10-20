package com.raikiservice.raf.quiz.web.dto;

import java.time.Instant;
import java.util.List;

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
 * {@code soloPrizeCredits} le prix d'un sans-faute ; les champs propres au
 * groupe (seuil, recompenses, sujets soumis au vote) sont sans objet.
 */
public record QuizResponse(
        Long id,
        String title,
        String slug,
        String description,
        String themeLabel,
        String bannerUrl,
        QuizStatus status,
        GameMode gameMode,
        TopicMode topicMode,
        Long selectedCategoryId,
        String selectedCategoryName,
        int ticketPriceCredits,
        long ticketPriceHtg,
        int minPlayers,
        Integer maxTicketsTotal,
        Integer maxTicketsPerPlayer,
        PlayMode playMode,
        int questionDurationSeconds,
        int questionsPerGame,
        int pointsPerCorrect,
        Long soloPrizeCredits,
        Long soloPrizeHtg,
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
        List<TopicView> topics) {

    public record RewardView(int rankFrom, int rankTo, long amountCredits, long amountHtg,
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
                quiz.getStatus(),
                quiz.getGameMode(),
                quiz.getTopicMode(),
                quiz.getSelectedCategory() == null ? null : quiz.getSelectedCategory().getId(),
                quiz.getSelectedCategory() == null ? null : quiz.getSelectedCategory().getName(),
                quiz.getTicketPriceCredits(),
                quiz.getTicketPriceCredits() * htgRate,
                quiz.getMinPlayers(),
                quiz.getMaxTicketsTotal(),
                quiz.getMaxTicketsPerPlayer(),
                quiz.getPlayMode(),
                quiz.getQuestionDurationSeconds(),
                quiz.getQuestionsPerGame(),
                quiz.getPointsPerCorrect(),
                quiz.getSoloPrizeCredits(),
                quiz.getSoloPrizeCredits() == null ? null : quiz.getSoloPrizeCredits() * htgRate,
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
                                reward.getAmountCredits(), reward.getAmountCredits() * htgRate,
                                reward.getLabel()))
                        .toList(),
                topics.stream()
                        .map(topic -> new TopicView(topic.getId(), topic.getCategory().getId(),
                                topic.getLabel(),
                                topic.getMedia() == null ? null : topic.getMedia().getUrl(),
                                topic.getVoteCount()))
                        .toList());
    }
}
