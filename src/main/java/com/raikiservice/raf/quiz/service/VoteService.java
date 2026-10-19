package com.raikiservice.raf.quiz.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizTopic;
import com.raikiservice.raf.quiz.domain.TopicMode;
import com.raikiservice.raf.quiz.domain.TopicVote;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.QuizTopicRepository;
import com.raikiservice.raf.quiz.repository.TopicVoteRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vote du sujet.
 *
 * <p>Reserve aux detenteurs d'un ticket : le vote engage ceux qui ont paye. Cela
 * implique que les tickets soient en vente avant que le sujet soit connu, d'ou le
 * theme general annonce des la publication.
 */
@Service
@RequiredArgsConstructor
public class VoteService {

    private final TopicVoteRepository topicVoteRepository;
    private final QuizTopicRepository quizTopicRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final TicketService ticketService;
    private final AuditService auditService;

    @Transactional
    public QuizTopic vote(Long userId, Long quizId, Long topicId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (!quiz.isVoteOpen(Instant.now())) {
            throw new ApiException(ErrorCode.VOTE_CLOSED);
        }
        if (!ticketService.holdsTicket(userId, quizId)) {
            throw new ApiException(ErrorCode.VOTE_REQUIRES_TICKET);
        }
        if (topicVoteRepository.findByQuizIdAndUserId(quizId, userId).isPresent()) {
            throw new ApiException(ErrorCode.VOTE_ALREADY_CAST);
        }

        QuizTopic topic = quizTopicRepository.findById(topicId)
                .orElseThrow(() -> ApiException.notFound("Sujet"));
        if (!topic.getQuiz().getId().equals(quizId)) {
            throw ApiException.notFound("Sujet");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));

        TopicVote vote = new TopicVote();
        vote.setQuiz(quiz);
        vote.setTopic(topic);
        vote.setUser(user);

        try {
            topicVoteRepository.saveAndFlush(vote);
        } catch (DataIntegrityViolationException e) {
            // Deux votes envoyes en meme temps : c'est la contrainte d'unicite en
            // base qui tranche, pas le controle applicatif ci-dessus.
            throw new ApiException(ErrorCode.VOTE_ALREADY_CAST);
        }

        topic.setVoteCount(topic.getVoteCount() + 1);
        return topic;
    }

    @Transactional(readOnly = true)
    public List<QuizTopic> results(Long quizId) {
        return quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId);
    }

    /**
     * Arrete le sujet du quiz.
     *
     * <p>L'administrateur peut suivre le vote ou imposer un autre sujet : c'est le
     * droit de veto prevu. Le sujet impose doit tout de meme figurer parmi les
     * candidats annonces, pour ne pas changer les regles apres coup.
     */
    @Transactional
    public Quiz closeVote(Long actorId, Long quizId, Long overrideTopicId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getTopicMode() != TopicMode.VOTE) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Ce quiz n'est pas soumis au vote.");
        }
        if (quiz.getSelectedCategory() != null) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE, "Le sujet est deja arrete.");
        }

        List<QuizTopic> topics = quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId);
        if (topics.isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_TOPICS_REQUIRED);
        }

        QuizTopic winner;
        if (overrideTopicId != null) {
            winner = topics.stream()
                    .filter(topic -> topic.getId().equals(overrideTopicId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("Sujet"));
        } else {
            // Egalite departagee par l'ordre de proposition, deterministe et
            // verifiable : le tri est vote_count DESC, id ASC.
            winner = topics.stream()
                    .max(Comparator.comparingInt(QuizTopic::getVoteCount)
                            .thenComparing(Comparator.comparingLong(QuizTopic::getId).reversed()))
                    .orElseThrow();
        }

        quiz.setSelectedCategory(winner.getCategory());
        quiz.setVoteClosesAt(Instant.now());

        auditService.record(actorId, "QUIZ_VOTE_CLOSED", "Quiz", String.valueOf(quizId), null,
                "sujet retenu : " + winner.getLabel() + " (" + winner.getVoteCount() + " voix)"
                        + (overrideTopicId != null ? " [choix administrateur]" : ""));

        return quiz;
    }
}
