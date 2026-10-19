package com.raikiservice.raf.quiz;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizTopic;
import com.raikiservice.raf.quiz.domain.TopicMode;
import com.raikiservice.raf.quiz.repository.QuizTopicRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.service.VoteService;
import com.raikiservice.raf.quiz.web.dto.QuizDto;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.support.IntegrationTest;
import com.raikiservice.raf.support.TestData;
import com.raikiservice.raf.wallet.domain.BalanceType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Lot 5 : des votes simultanes pour le meme sujet sont tous comptes. */
class VoteConcurrencyTest extends IntegrationTest {

    @Autowired private QuizService quizService;
    @Autowired private RewardService rewardService;
    @Autowired private TicketService ticketService;
    @Autowired private VoteService voteService;
    @Autowired private QuizTopicRepository quizTopicRepository;

    @Test
    void simultaneousVotesForTheSameTopicAreAllCounted() throws Exception {
        Long first = data.categoryWithQuestions(30);
        Long second = data.categoryWithQuestions(30);

        Quiz quiz = quizService.create(null, new QuizDto("Quiz vote " + UUID.randomUUID(), null,
                "Culture generale", TopicMode.VOTE, null, null, TestData.TICKET_PRICE, 1000,
                null, null, 0, 60, 3, 10, 1000, 1));
        rewardService.configure(null, quiz, List.of(new RewardTierDto(1, 1, 100, null)));
        quizService.configureTopics(null, quiz.getId(), List.of(first, second));
        quizService.publish(null, quiz.getId(), 60);

        List<User> voters = List.of(data.player(), data.player(), data.player(), data.player());
        for (User voter : voters) {
            data.credit(voter.getId(), BalanceType.CREDIT, TestData.TICKET_PRICE);
            ticketService.purchase(voter.getId(), quiz.getId(), UUID.randomUUID().toString());
        }
        Long topicId = quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quiz.getId())
                .get(0).getId();

        AtomicInteger next = new AtomicInteger();
        List<QuizTopic> cast = concurrently(voters.size(), () ->
                voteService.vote(voters.get(next.getAndIncrement()).getId(), quiz.getId(), topicId));

        assertThat(cast).hasSize(voters.size());
        assertThat(quizTopicRepository.findById(topicId).orElseThrow().getVoteCount())
                .isEqualTo(voters.size());
    }
}
