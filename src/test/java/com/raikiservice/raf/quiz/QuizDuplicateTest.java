package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;

import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizReward;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.QuizTopic;
import com.raikiservice.raf.quiz.domain.TopicMode;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.QuizTopicRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.quiz.web.dto.QuizDto;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Duplication d'un quiz dans un nouveau brouillon, sans son historique. */
class QuizDuplicateTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private RewardService rewardService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizTopicRepository quizTopicRepository;

    @Test
    void aCancelledGroupQuizIsCopiedWithItsRulesAndRewards() {
        Long categoryId = data.categoryWithQuestions(30);
        Long sourceId = data.publishedQuiz(categoryId, 3, 1000, 5, 50, 2);
        quizService.cancel(null, sourceId, "erreur de prix");

        Quiz copy = quizService.duplicate(null, sourceId);

        Quiz source = quizRepository.findById(sourceId).orElseThrow();
        assertThat(copy.getId()).isNotEqualTo(sourceId);
        assertThat(copy.getStatus()).isEqualTo(QuizStatus.DRAFT);
        assertThat(copy.getTitle()).isEqualTo(source.getTitle() + " (copie)");
        assertThat(copy.getSlug()).isNotEqualTo(source.getSlug());
        assertThat(copy.getGameMode()).isEqualTo(GameMode.GROUP);
        assertThat(copy.getSelectedCategory().getId()).isEqualTo(categoryId);
        assertThat(copy.getTicketPriceCredits()).isEqualTo(source.getTicketPriceCredits());
        assertThat(copy.getMinPlayers()).isEqualTo(5);
        assertThat(copy.getMaxTicketsTotal()).isEqualTo(50);
        assertThat(copy.getMaxTicketsPerPlayer()).isEqualTo(2);
        assertThat(copy.getPlayMode()).isEqualTo(source.getPlayMode());
        assertThat(copy.getQuestionDurationSeconds()).isEqualTo(source.getQuestionDurationSeconds());
        assertThat(copy.getCancellationReason()).isNull();
        assertThat(rewardService.forQuiz(copy.getId()))
                .extracting(QuizReward::getRankFrom, QuizReward::getAmountHtg)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, TestData.FIRST_PRIZE),
                        org.assertj.core.groups.Tuple.tuple(2, TestData.SECOND_PRIZE));

        // L'original garde son etat, et la copie se publie normalement.
        assertThat(source.getStatus()).isEqualTo(QuizStatus.CANCELLED);
        quizService.publish(null, copy.getId(), null);
        assertThat(quizRepository.findById(copy.getId()).orElseThrow().getStatus())
                .isEqualTo(QuizStatus.REGISTRATION);
    }

    @Test
    void aVoteQuizIsCopiedWithFreshTopics() {
        Long first = data.categoryWithQuestions(30);
        Long second = data.categoryWithQuestions(30);
        Quiz source = quizService.create(null, new QuizDto("Quiz vote " + UUID.randomUUID(), null,
                "Culture generale", TopicMode.VOTE, null, null, TestData.TICKET_PRICE, 1000,
                null, null, 0, PlayMode.SEQUENTIAL, TestData.QUESTION_SECONDS, 3, 10, 1000, 1));
        rewardService.configure(null, source, List.of(new RewardTierDto(1, 1, 100, null)));
        quizService.configureTopics(null, source.getId(), List.of(first, second));

        Quiz copy = quizService.duplicate(null, source.getId());

        assertThat(copy.getTopicMode()).isEqualTo(TopicMode.VOTE);
        assertThat(copy.getSelectedCategory()).isNull();
        assertThat(quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(copy.getId()))
                .extracting(topic -> topic.getCategory().getId(), QuizTopic::getVoteCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(first, 0),
                        org.assertj.core.groups.Tuple.tuple(second, 0));
    }

    @Test
    void aClosedSoloQuizIsCopiedIntoADraftThatCanBeReopened() {
        Long categoryId = data.categoryWithQuestions(30);
        Long sourceId = data.soloQuiz(categoryId, 3);
        quizService.closeSolo(null, sourceId);

        Quiz copy = quizService.duplicate(null, sourceId);

        assertThat(copy.getStatus()).isEqualTo(QuizStatus.DRAFT);
        assertThat(copy.getGameMode()).isEqualTo(GameMode.SOLO);
        assertThat(copy.getPlayMode()).isEqualTo(PlayMode.SEQUENTIAL);
        assertThat(copy.getSoloPrizeHtg()).isEqualTo(TestData.SOLO_PRIZE);
        assertThat(copy.getTicketPriceCredits()).isEqualTo(TestData.SOLO_ENTRY);
        assertThat(copy.getStartedAt()).isNull();
        assertThat(copy.getFinishedAt()).isNull();
        assertThat(quizService.poolSize(copy.getId())).isZero();

        // A la reouverture, la copie fige son propre vivier.
        quizService.publish(null, copy.getId(), null);
        assertThat(quizRepository.findById(copy.getId()).orElseThrow().getStatus())
                .isEqualTo(QuizStatus.RUNNING);
        assertThat(quizService.poolSize(copy.getId())).isEqualTo(30);
    }
}
