package com.raikiservice.raf.quiz.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizQuestionPool;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.QuizTopic;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.domain.TopicMode;
import com.raikiservice.raf.quiz.repository.QuizQuestionPoolRepository;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.QuizTopicRepository;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.web.dto.QuizDto;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle de vie du quiz.
 *
 * <p>Toutes les transitions passent par ici. Aucun autre service ne modifie
 * {@code quiz.status}, a deux exceptions documentees : {@link TicketService}
 * declenche le passage en SCHEDULED quand le seuil de joueurs est atteint (il
 * detient deja le verrou du quiz a cet instant), et {@code RankingService}
 * (module game) fait passer COMPUTING -> FINISHED en figeant le classement, sous
 * le meme verrou que le versement des gains. Le passage en CLOSING est
 * declenche par le jeu, mais via {@link #triggerClosing}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizTopicRepository quizTopicRepository;
    private final QuizQuestionPoolRepository quizQuestionPoolRepository;
    private final QuestionRepository questionRepository;
    private final QuestionService questionService;
    private final CategoryService categoryService;
    private final RewardService rewardService;
    private final TicketService ticketService;
    private final TicketRepository ticketRepository;
    private final WalletService walletService;
    private final MediaService mediaService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public Quiz require(Long id) {
        return quizRepository.findById(id).orElseThrow(() -> ApiException.notFound("Quiz"));
    }

    @Transactional(readOnly = true)
    public Page<Quiz> byStatus(QuizStatus status, Pageable pageable) {
        return quizRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    /** Quiz visibles d'un joueur : tout sauf les brouillons. */
    @Transactional(readOnly = true)
    public Page<Quiz> visibleToPlayers(Pageable pageable) {
        return quizRepository.findByStatusInOrderByCreatedAtDesc(
                List.of(QuizStatus.REGISTRATION, QuizStatus.SCHEDULED, QuizStatus.RUNNING,
                        QuizStatus.CLOSING, QuizStatus.COMPUTING, QuizStatus.FINISHED),
                pageable);
    }

    @Transactional
    public Quiz create(Long actorId, QuizDto request) {
        Quiz quiz = new Quiz();
        quiz.setCreatedBy(actorId);
        quiz.setSlug(uniqueSlug(request.title()));
        applyFields(quiz, request);

        Quiz saved = quizRepository.save(quiz);
        auditService.record(actorId, "QUIZ_CREATED", "Quiz", String.valueOf(saved.getId()),
                null, request.title());
        return saved;
    }

    @Transactional
    public Quiz update(Long actorId, Long id, QuizDto request) {
        Quiz quiz = require(id);

        // Un quiz publie a deja des tickets vendus sur la foi de ses regles :
        // prix, seuil et recompenses ne peuvent plus bouger.
        if (quiz.getStatus() != QuizStatus.DRAFT) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Un quiz publie ne peut plus etre modifie. Annulez-le ou creez-en un nouveau.");
        }

        applyFields(quiz, request);
        auditService.record(actorId, "QUIZ_UPDATED", "Quiz", String.valueOf(id), null,
                request.title());
        return quiz;
    }

    @Transactional
    public List<QuizTopic> configureTopics(Long actorId, Long quizId, List<Long> categoryIds) {
        Quiz quiz = require(quizId);
        assertDraft(quiz);

        if (categoryIds == null || categoryIds.size() < 2) {
            throw new ApiException(ErrorCode.QUIZ_TOPICS_REQUIRED);
        }

        quizTopicRepository.deleteAll(
                quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId));
        quizTopicRepository.flush();

        List<QuizTopic> topics = new ArrayList<>();
        for (Long categoryId : categoryIds) {
            Category category = categoryService.require(categoryId);

            QuizTopic topic = new QuizTopic();
            topic.setQuiz(quiz);
            topic.setCategory(category);
            topic.setLabel(category.getName());
            topic.setMedia(category.getMedia());
            topics.add(quizTopicRepository.save(topic));
        }

        auditService.record(actorId, "QUIZ_TOPICS_CONFIGURED", "Quiz", String.valueOf(quizId),
                null, categoryIds.toString());
        return topics;
    }

    /**
     * Publie le quiz : les tickets partent en vente.
     *
     * <p>Trois verrous avant publication, parce qu'apres il sera trop tard :
     * recompenses coherentes, sujet exploitable, vivier de questions suffisant.
     */
    @Transactional
    public Quiz publish(Long actorId, Long quizId, Integer voteDurationMinutes) {
        Quiz quiz = require(quizId);
        assertDraft(quiz);

        if (rewardService.forQuiz(quizId).isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_NO_REWARDS);
        }

        if (quiz.getTopicMode() == TopicMode.ADMIN) {
            if (quiz.getSelectedCategory() == null) {
                throw new ApiException(ErrorCode.QUIZ_NO_TOPIC,
                        "Choisissez une categorie ou passez le quiz en mode vote.");
            }
            assertPoolSufficient(quiz, quiz.getSelectedCategory().getId());
        } else {
            List<QuizTopic> topics = quizTopicRepository
                    .findByQuizIdOrderByVoteCountDescIdAsc(quizId);
            if (topics.size() < 2) {
                throw new ApiException(ErrorCode.QUIZ_TOPICS_REQUIRED);
            }
            // Chaque sujet candidat doit pouvoir accueillir le quiz : sinon le vote
            // peut designer une categorie sur laquelle il sera impossible de jouer.
            for (QuizTopic topic : topics) {
                assertPoolSufficient(quiz, topic.getCategory().getId());
            }

            Instant now = Instant.now();
            quiz.setVoteOpensAt(now);
            quiz.setVoteClosesAt(now.plus(
                    voteDurationMinutes == null ? 1440 : voteDurationMinutes, ChronoUnit.MINUTES));
        }

        quiz.setStatus(QuizStatus.REGISTRATION);

        auditService.record(actorId, "QUIZ_PUBLISHED", "Quiz", String.valueOf(quizId),
                QuizStatus.DRAFT.name(), QuizStatus.REGISTRATION.name());
        return quiz;
    }

    /**
     * Demarre le quiz : fige le vivier de questions, puis ouvre le jeu.
     *
     * <p>Le vivier est copie a cet instant precis. Une question publiee ou
     * archivee ensuite n'entrera ni ne sortira du quiz en cours : tous les joueurs
     * tirent dans le meme ensemble, du premier au dernier.
     */
    @Transactional
    public Quiz start(Long actorId, Long quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() != QuizStatus.SCHEDULED) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Seul un quiz programme peut demarrer.");
        }

        Category category = resolveCategory(quiz);
        int frozen = freezePool(quiz, category.getId());

        quiz.setStartedAt(Instant.now());
        quiz.setStatus(QuizStatus.RUNNING);

        auditService.record(actorId, "QUIZ_STARTED", "Quiz", String.valueOf(quizId),
                QuizStatus.SCHEDULED.name(), frozen + " questions figees");
        log.info("Quiz {} demarre avec un vivier de {} questions", quizId, frozen);

        notificationService.notifyUsers(ticketRepository.findHolderIds(quizId),
                NotificationMessages.quizStarted(quiz));

        return quiz;
    }

    /**
     * Declenche le compte a rebours de cloture.
     *
     * <p>Appele par le jeu quand un joueur depasse le score minimum. Les
     * inscriptions et les parties restent ouvertes pendant le decompte.
     *
     * <p>Etat et seuil sont re-verifies sous verrou : deux parties qui franchissent
     * le seuil en meme temps ne lancent qu'un seul compte a rebours.
     */
    @Transactional
    public Quiz triggerClosing(Long quizId, int triggeredByScore) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() != QuizStatus.RUNNING
                || triggeredByScore <= quiz.getMinScoreThreshold()) {
            return quiz;
        }

        Instant now = Instant.now();
        quiz.setClosingTriggeredAt(now);
        quiz.setEndsAt(now.plus(quiz.getClosingCountdownMinutes(), ChronoUnit.MINUTES));
        quiz.setStatus(QuizStatus.CLOSING);

        auditService.record(null, "QUIZ_CLOSING_TRIGGERED", "Quiz", String.valueOf(quizId),
                null, "score declencheur : " + triggeredByScore);
        log.info("Quiz {} : score minimum depasse, cloture le {}", quizId, quiz.getEndsAt());

        notificationService.notifyUsers(ticketRepository.findHolderIds(quizId),
                NotificationMessages.quizClosing(quiz));

        return quiz;
    }

    @Transactional
    public Quiz moveToComputing(Long quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() != QuizStatus.CLOSING) {
            return quiz;
        }

        quiz.setStatus(QuizStatus.COMPUTING);
        auditService.record(null, "QUIZ_COMPUTING", "Quiz", String.valueOf(quizId),
                QuizStatus.CLOSING.name(), QuizStatus.COMPUTING.name());
        return quiz;
    }

    /** Annulation : rembourse integralement les tickets deja vendus. */
    @Transactional
    public Quiz cancel(Long actorId, Long quizId, String reason) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (!quiz.isCancellable()) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Un quiz deja demarre ne peut plus etre annule.");
        }

        int refunded = ticketService.refundAll(actorId, quiz, reason);

        quiz.setStatus(QuizStatus.CANCELLED);
        quiz.setCancellationReason(reason);

        auditService.record(actorId, "QUIZ_CANCELLED", "Quiz", String.valueOf(quizId),
                null, reason + " (" + refunded + " ticket(s) rembourse(s))");

        notificationService.notifyUsers(ticketRepository.findBuyerIds(quizId),
                NotificationMessages.quizCancelled(quiz, reason));
        return quiz;
    }

    @Transactional(readOnly = true)
    public List<QuizTopic> topics(Long quizId) {
        return quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId);
    }

    /**
     * Assemble la vue complete d'un quiz.
     *
     * <p>Toujours appele dans une transaction : la vue touche la categorie, la
     * banniere, les sujets et leurs images, toutes paresseuses. Les assembler dans
     * le controleur leverait une LazyInitializationException.
     */
    @Transactional(readOnly = true)
    public QuizResponse view(Long quizId) {
        return toResponse(require(quizId));
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewPage(Page<Quiz> page) {
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewVisible(Pageable pageable) {
        return visibleToPlayers(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewByStatus(QuizStatus status, Pageable pageable) {
        return byStatus(status, pageable).map(this::toResponse);
    }

    QuizResponse toResponse(Quiz quiz) {
        return QuizResponse.from(
                quiz,
                rewardService.forQuiz(quiz.getId()),
                quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quiz.getId()),
                ticketRepository.countDistinctPlayers(quiz.getId()),
                ticketRepository.countByQuizIdAndStatusNot(quiz.getId(), TicketStatus.REFUNDED),
                quizQuestionPoolRepository.countByQuizId(quiz.getId()),
                walletService.htgRate());
    }

    @Transactional(readOnly = true)
    public long poolSize(Long quizId) {
        return quizQuestionPoolRepository.countByQuizId(quizId);
    }

    Category resolveCategory(Quiz quiz) {
        if (quiz.getSelectedCategory() != null) {
            return quiz.getSelectedCategory();
        }
        throw new ApiException(ErrorCode.QUIZ_NO_TOPIC,
            "Le sujet doit etre arrete avant le demarrage : cloturez le vote.");
    }

    private int freezePool(Quiz quiz, Long categoryId) {
        List<Question> questions = questionRepository
                .findByCategoryIdAndStatus(categoryId, QuestionStatus.PUBLISHED,
                        Pageable.unpaged())
                .getContent();

        for (Question question : questions) {
            QuizQuestionPool entry = new QuizQuestionPool();
            entry.setQuiz(quiz);
            entry.setQuestion(question);
            quizQuestionPoolRepository.save(entry);
        }
        return questions.size();
    }

    private void assertPoolSufficient(Quiz quiz, Long categoryId) {
        QuestionService.PoolStatus pool = questionService.poolStatus(
                categoryId, quiz.getQuestionsPerGame());

        if (!pool.sufficient()) {
            throw new ApiException(ErrorCode.QUIZ_POOL_INSUFFICIENT,
                    "La categorie " + categoryId + " compte " + pool.available()
                            + " question(s) publiee(s) ; il en faut " + pool.required()
                            + " pour servir " + quiz.getQuestionsPerGame() + " questions par partie.");
        }
    }

    private void applyFields(Quiz quiz, QuizDto request) {
        quiz.setTitle(request.title().trim());
        quiz.setDescription(request.description());
        quiz.setThemeLabel(request.themeLabel());
        quiz.setTopicMode(request.topicMode() == null ? TopicMode.ADMIN : request.topicMode());
        quiz.setSelectedCategory(request.categoryId() == null ? null
                : categoryService.require(request.categoryId()));
        quiz.setBanner(request.bannerMediaId() == null ? null
                : mediaService.require(request.bannerMediaId()));
        quiz.setTicketPriceCredits(request.ticketPriceCredits());
        quiz.setMinPlayers(request.minPlayers());
        quiz.setMaxTicketsTotal(request.maxTicketsTotal());
        quiz.setMaxTicketsPerPlayer(request.maxTicketsPerPlayer());
        quiz.setStartDelayMinutes(request.startDelayMinutes());
        quiz.setGameDurationSeconds(request.gameDurationSeconds());
        quiz.setQuestionsPerGame(request.questionsPerGame());
        quiz.setPointsPerCorrect(request.pointsPerCorrect());
        quiz.setMinScoreThreshold(request.minScoreThreshold());
        quiz.setClosingCountdownMinutes(request.closingCountdownMinutes());
    }

    private void assertDraft(Quiz quiz) {
        if (quiz.getStatus() != QuizStatus.DRAFT) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Action reservee a un quiz en brouillon.");
        }
    }

    private String uniqueSlug(String title) {
        String base = com.raikiservice.raf.question.service.CategoryService.slugify(title);
        String slug = base;
        int suffix = 2;
        while (quizRepository.existsBySlug(slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }
}
