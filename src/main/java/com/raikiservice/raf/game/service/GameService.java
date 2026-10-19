package com.raikiservice.raf.game.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.game.domain.GameAnswer;
import com.raikiservice.raf.game.domain.GameSession;
import com.raikiservice.raf.game.domain.GameSessionQuestion;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.game.repository.GameAnswerRepository;
import com.raikiservice.raf.game.repository.GameSessionQuestionRepository;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameReviewResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.game.web.dto.SubmitGameDto.AnswerDto;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.QuizQuestionPoolRepository;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.service.QuizService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deroule d'une partie, selon le modele de revelation differee.
 *
 * <p>Au demarrage, le serveur tire les questions, fige le tirage et le corrige,
 * puis envoie les enonces <em>sans</em> indicateur de bonne reponse. Le joueur
 * joue hors-ligne et renvoie toutes ses reponses d'un bloc. Le score est alors
 * recalcule ici a partir du tirage fige : la valeur que pourrait annoncer le
 * client n'existe meme pas dans l'API. Le corrige detaille n'est accessible
 * qu'une fois le quiz termine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private static final long DEFAULT_GRACE_SECONDS = 15L;
    private static final long DEFAULT_MIN_ANSWER_MS = 400L;
    private static final long DEFAULT_FAST_ANSWER_FLAG_COUNT = 5L;

    /**
     * Tolerance sur les temps declares : le chrono du client et l'arrondi de son
     * affichage ne sont pas a la milliseconde pres. Une seconde ne change rien a
     * la triche possible mais evite d'invalider un joueur honnete.
     */
    private static final long CLOCK_TOLERANCE_MS = 1_000L;

    private final SecureRandom random = new SecureRandom();

    private final GameSessionRepository gameSessionRepository;
    private final GameSessionQuestionRepository gameSessionQuestionRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionPoolRepository quizQuestionPoolRepository;
    private final TicketRepository ticketRepository;
    private final QuestionRepository questionRepository;
    private final QuizService quizService;
    private final QuestionStatsRecorder questionStatsRecorder;
    private final SettingsService settingsService;
    private final AuditService auditService;

    // --- Demarrage ------------------------------------------------------------

    /**
     * Lance une partie, ou reprend celle qui est en cours.
     *
     * <p>La reprise est essentielle : un rechargement de page ne doit pas bruler
     * un second ticket. Le joueur retrouve le meme tirage et le chrono la ou il
     * en etait.
     *
     * <p>Ordre des verrous : quiz (partage) puis ticket (exclusif). Le verrou
     * partage laisse les parties demarrer en parallele, mais la cloture du quiz,
     * qui prend un verrou exclusif, attend que les demarrages en vol soient
     * enregistres. Le ticket choisi est toujours le plus ancien : deux clics
     * simultanes visent donc le meme ticket, et le second attend le premier au
     * lieu d'en consommer un autre.
     */
    @Transactional
    public GameStartResponse start(Long userId, Long quizId, String ip, String userAgent) {
        Quiz quiz = quizRepository.findByIdForShare(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        Instant now = Instant.now();
        if (!quiz.acceptsPlay() || (quiz.getEndsAt() != null && !now.isBefore(quiz.getEndsAt()))) {
            throw new ApiException(ErrorCode.GAME_NOT_PLAYABLE);
        }

        Optional<GameSession> ongoing = gameSessionRepository
                .findFirstByQuizIdAndUserIdAndStatusAndServerDeadlineAtAfterOrderByStartedAtDesc(
                        quizId, userId, GameSessionStatus.IN_PROGRESS, now);
        if (ongoing.isPresent()) {
            return view(ongoing.get(), true, now);
        }

        List<Long> candidates = ticketRepository.findActiveTicketIds(
                quizId, userId, PageRequest.of(0, 1));
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.GAME_NO_TICKET);
        }

        Ticket ticket = ticketRepository.findByIdForUpdate(candidates.get(0))
                .orElseThrow(() -> ApiException.notFound("Ticket"));

        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            // Une requete concurrente (double clic) vient de consommer ce ticket.
            // Lecture verrouillee : elle voit la partie que l'autre requete a
            // creee, la ou une lecture simple resterait sur l'instantane de debut
            // de transaction.
            return gameSessionRepository.findByTicketIdForShare(ticket.getId())
                    .filter(session -> session.isOpenAt(now))
                    .map(session -> view(session, true, now))
                    .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                            "Ce ticket vient d'etre utilise. Reessayez."));
        }

        return open(quiz, ticket, now, ip, userAgent);
    }

    private GameStartResponse open(Quiz quiz, Ticket ticket, Instant now, String ip,
            String userAgent) {

        List<Long> pool = new ArrayList<>(quizQuestionPoolRepository.findQuestionIds(quiz.getId()));
        if (pool.isEmpty()) {
            log.error("Quiz {} en cours sans vivier de questions", quiz.getId());
            throw new ApiException(ErrorCode.GAME_NOT_PLAYABLE);
        }

        // Tirage independant par joueur dans un vivier bien plus large que la
        // partie : c'est la premiere barriere contre la fuite des enonces.
        Collections.shuffle(pool, random);
        List<Long> picked = pool.subList(0, Math.min(quiz.getQuestionsPerGame(), pool.size()));
        if (picked.size() < quiz.getQuestionsPerGame()) {
            log.warn("Quiz {} : vivier de {} question(s), {} prevue(s) par partie",
                    quiz.getId(), pool.size(), quiz.getQuestionsPerGame());
        }

        Map<Long, Question> questions = questionRepository.findWithChoicesByIdIn(picked).stream()
                .collect(Collectors.toMap(Question::getId, Function.identity()));

        ticket.setStatus(TicketStatus.USED);
        ticket.setUsedAt(now);

        long grace = settingsService.getLong(
                SettingKeys.GAME_NETWORK_GRACE_SECONDS, DEFAULT_GRACE_SECONDS);

        GameSession session = new GameSession();
        session.setQuiz(quiz);
        session.setUser(ticket.getUser());
        session.setTicket(ticket);
        session.setStatus(GameSessionStatus.IN_PROGRESS);
        session.setDurationSeconds(quiz.getGameDurationSeconds());
        session.setPointsPerCorrect(quiz.getPointsPerCorrect());
        session.setStartedAt(now);
        session.setServerDeadlineAt(now.plusSeconds(quiz.getGameDurationSeconds() + grace));
        session.setIp(ip);
        session.setUserAgent(userAgent);
        gameSessionRepository.save(session);

        List<GameSessionQuestion> draws = new ArrayList<>();
        int position = 1;
        for (Long questionId : picked) {
            Question question = questions.get(questionId);

            GameSessionQuestion draw = new GameSessionQuestion();
            draw.setSession(session);
            draw.setQuestion(question);
            draw.setPosition(position++);
            draw.setCorrectChoiceId(question.correctChoice().getId());
            draws.add(gameSessionQuestionRepository.save(draw));
        }

        recordStatsAfterCommit(picked, List.of());

        log.debug("Partie {} lancee : joueur {}, quiz {}, {} question(s)",
                session.getId(), ticket.getUser().getId(), quiz.getId(), draws.size());

        return view(session, draws, questions, false, now);
    }

    private GameStartResponse view(GameSession session, boolean resumed, Instant now) {
        List<GameSessionQuestion> draws =
                gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(session.getId());
        return view(session, draws, loadQuestions(draws), resumed, now);
    }

    private GameStartResponse view(GameSession session, List<GameSessionQuestion> draws,
            Map<Long, Question> questions, boolean resumed, Instant now) {

        List<GameStartResponse.QuestionView> views = new ArrayList<>();
        for (GameSessionQuestion draw : draws) {
            Question question = questions.get(draw.getQuestion().getId());

            List<GameStartResponse.ChoiceView> choices = shuffledChoices(session, question).stream()
                    .map(choice -> new GameStartResponse.ChoiceView(
                            choice.getId(),
                            choice.getText(),
                            choice.getMedia() == null ? null : choice.getMedia().getUrl()))
                    .toList();

            views.add(new GameStartResponse.QuestionView(
                    question.getId(),
                    draw.getPosition(),
                    question.getText(),
                    question.getMedia() == null ? null : question.getMedia().getUrl(),
                    question.getType(),
                    choices));
        }

        long remaining = Math.max(0, Duration.between(now, session.playEndsAt()).toMillis());

        return new GameStartResponse(
                session.getId(),
                session.getQuiz().getId(),
                resumed,
                session.getDurationSeconds(),
                session.getStartedAt(),
                session.playEndsAt(),
                remaining,
                views);
    }

    /**
     * Ordre des propositions melange par partie, pour qu'un enonce partage entre
     * joueurs ne circule pas avec "c'est la reponse B".
     *
     * <p>Le melange est deterministe (graine = partie + question) : une reprise
     * reaffiche les propositions dans le meme ordre. Connaitre la graine
     * n'apprend rien sur la bonne reponse. Le vrai/faux garde son ordre naturel.
     */
    private List<QuestionChoice> shuffledChoices(GameSession session, Question question) {
        List<QuestionChoice> choices = new ArrayList<>(question.getChoices());
        if (question.getType() != QuestionType.TRUE_FALSE) {
            Collections.shuffle(choices, new Random(session.getId() * 1_000_003L + question.getId()));
        }
        return choices;
    }

    // --- Soumission -----------------------------------------------------------

    /**
     * Corrige une partie.
     *
     * <p>Toute incoherence invalide la partie au lieu de lever une erreur : une
     * exception annulerait la transaction, et donc l'invalidation elle-meme, et le
     * joueur pourrait resoumettre jusqu'a passer les controles.
     *
     * <p>Le quiz n'est jamais charge ici (duree et bareme sont copies dans la
     * partie). C'est ce qui garantit que le verrou pris par
     * {@link QuizService#triggerClosing} est la premiere lecture du quiz dans la
     * transaction, et donc qu'il lit un etat a jour.
     */
    @Transactional
    public GameResultResponse submit(Long userId, SubmitGameDto request) {
        GameSession session = gameSessionRepository.findByIdForUpdate(request.sessionId())
                .orElseThrow(() -> ApiException.notFound("Partie"));

        if (!session.getUser().getId().equals(userId)) {
            throw ApiException.notFound("Partie");
        }

        Long quizId = session.getQuiz().getId();

        switch (session.getStatus()) {
            case SUBMITTED, INVALIDATED -> {
                // Rejeu (nouvel essai reseau, double clic) : meme reponse, rien de recalcule.
                return result(session, userId);
            }
            case EXPIRED -> throw new ApiException(ErrorCode.GAME_TIME_EXPIRED);
            case IN_PROGRESS -> {
                // Cas nominal, traite ci-dessous.
            }
        }

        Instant now = Instant.now();
        if (now.isAfter(session.getServerDeadlineAt())) {
            throw new ApiException(ErrorCode.GAME_TIME_EXPIRED);
        }

        List<GameSessionQuestion> draws =
                gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(session.getId());
        List<AnswerDto> answers = request.answers();

        String violation = findViolation(session, draws, answers, loadQuestions(draws), now);
        if (violation != null) {
            invalidate(session, violation, now, null);
            log.warn("Partie {} invalidee : {}", session.getId(), violation);
            return result(session, userId);
        }

        int correctCount = 0;
        List<Long> correctQuestionIds = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            AnswerDto answer = answers.get(i);
            GameSessionQuestion draw = draws.get(i);
            boolean correct = draw.getCorrectChoiceId().equals(answer.choiceId());

            GameAnswer entry = new GameAnswer();
            entry.setSession(session);
            entry.setQuestionId(answer.questionId());
            entry.setChoiceId(answer.choiceId());
            entry.setCorrect(correct);
            entry.setElapsedMs(answer.elapsedMs());
            entry.setPointsAwarded(correct ? session.getPointsPerCorrect() : 0);
            gameAnswerRepository.save(entry);

            if (correct) {
                correctCount++;
                correctQuestionIds.add(answer.questionId());
            }
        }

        int score = correctCount * session.getPointsPerCorrect();

        session.setStatus(GameSessionStatus.SUBMITTED);
        session.setSubmittedAt(now);
        session.setClientDurationMs(request.clientDurationMs());
        session.setScore(score);
        session.setCorrectCount(correctCount);
        session.setAnsweredCount(answers.size());

        flagFastAnswers(session, answers);
        recordStatsAfterCommit(List.of(), correctQuestionIds);

        // Projection scalaire : ne charge pas le quiz (voir la javadoc).
        quizRepository.findMinScoreThreshold(quizId, QuizStatus.RUNNING)
                .filter(threshold -> score > threshold)
                .ifPresent(threshold -> quizService.triggerClosing(quizId, score));

        return result(session, userId);
    }

    /**
     * Controles de coherence. Renvoie le motif de la premiere violation, ou null.
     *
     * <p>Le jeu interdit de sauter une question : la reponse n doit porter sur la
     * question n du tirage. Ce seul controle couvre a la fois les questions hors
     * tirage, les doublons et le desordre.
     */
    private String findViolation(GameSession session, List<GameSessionQuestion> draws,
            List<AnswerDto> answers, Map<Long, Question> questions, Instant now) {

        if (answers.size() > draws.size()) {
            return "Plus de reponses (" + answers.size() + ") que de questions servies ("
                    + draws.size() + ").";
        }

        long maxElapsed = session.getDurationSeconds() * 1_000L + CLOCK_TOLERANCE_MS;
        long previous = 0;

        for (int i = 0; i < answers.size(); i++) {
            AnswerDto answer = answers.get(i);
            Long expectedQuestionId = draws.get(i).getQuestion().getId();

            if (!expectedQuestionId.equals(answer.questionId())) {
                return "Reponse " + (i + 1) + " : question " + answer.questionId()
                        + " au lieu de " + expectedQuestionId + ".";
            }

            Set<Long> validChoices = questions.get(expectedQuestionId).getChoices().stream()
                    .map(QuestionChoice::getId)
                    .collect(Collectors.toSet());
            if (!validChoices.contains(answer.choiceId())) {
                return "Reponse " + (i + 1) + " : le choix " + answer.choiceId()
                        + " n'appartient pas a la question.";
            }

            if (answer.elapsedMs() < previous) {
                return "Reponse " + (i + 1) + " : temps ecoule decroissant.";
            }
            if (answer.elapsedMs() > maxElapsed) {
                return "Reponse " + (i + 1) + " : temps ecoule superieur a la duree de la partie.";
            }
            previous = answer.elapsedMs();
        }

        // Le chrono du client demarre apres celui du serveur : il ne peut pas
        // avoir mesure plus de temps qu'il ne s'en est reellement ecoule.
        long serverElapsed = Duration.between(session.getStartedAt(), now).toMillis();
        if (previous > serverElapsed + CLOCK_TOLERANCE_MS) {
            return "Temps declare (" + previous + " ms) superieur au temps ecoule cote serveur ("
                    + serverElapsed + " ms).";
        }

        return null;
    }

    /**
     * Temps de reaction humainement improbables. Simple signalement pour revue :
     * un joueur rapide et sur de lui n'est pas un tricheur, on ne sanctionne donc
     * pas automatiquement.
     */
    private void flagFastAnswers(GameSession session, List<AnswerDto> answers) {
        long minAnswerMs = settingsService.getLong(
                SettingKeys.GAME_MIN_ANSWER_MS, DEFAULT_MIN_ANSWER_MS);
        long flagCount = settingsService.getLong(
                SettingKeys.GAME_FAST_ANSWER_FLAG_COUNT, DEFAULT_FAST_ANSWER_FLAG_COUNT);

        int fast = 0;
        long previous = 0;
        for (AnswerDto answer : answers) {
            if (answer.elapsedMs() - previous < minAnswerMs) {
                fast++;
            }
            previous = answer.elapsedMs();
        }

        if (fast >= flagCount) {
            session.setFlagged(true);
            session.setFlagReason(fast + " reponse(s) en moins de " + minAnswerMs + " ms");
            auditService.record(session.getUser().getId(), "GAME_SESSION_FLAGGED", "GameSession",
                    String.valueOf(session.getId()), null, session.getFlagReason());
        }
    }

    private void invalidate(GameSession session, String reason, Instant now, Long actorId) {
        session.setStatus(GameSessionStatus.INVALIDATED);
        session.setInvalidationReason(reason);
        if (session.getSubmittedAt() == null) {
            session.setSubmittedAt(now);
        }
        // Une partie invalidee par un administrateur garde son score d'origine,
        // pour la trace ; c'est le statut qui l'exclut du classement.
        if (session.getScore() == null) {
            session.setScore(0);
        }
        if (session.getCorrectCount() == null) {
            session.setCorrectCount(0);
            session.setAnsweredCount(0);
        }

        auditService.record(actorId, "GAME_SESSION_INVALIDATED", "GameSession",
                String.valueOf(session.getId()), null, reason);
    }

    private GameResultResponse result(GameSession session, Long userId) {
        Integer best = gameSessionRepository
                .bestScore(session.getQuiz().getId(), userId)
                .orElse(null);
        return GameResultResponse.from(session, best);
    }

    // --- Consultation ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GameResultResponse> mySessions(Long userId, Long quizId) {
        Integer best = gameSessionRepository.bestScore(quizId, userId).orElse(null);
        return gameSessionRepository.findByQuizIdAndUserIdOrderByStartedAtDesc(quizId, userId)
                .stream()
                .map(session -> GameResultResponse.from(session, best))
                .toList();
    }

    /**
     * Corrige detaille. Pour un joueur, uniquement une fois le quiz termine :
     * avant, le corrige d'un joueur deviendrait l'antiseche des suivants.
     */
    @Transactional(readOnly = true)
    public GameReviewResponse review(Long viewerId, Long sessionId, boolean staff) {
        GameSession session = gameSessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Partie"));

        if (!staff) {
            if (!session.getUser().getId().equals(viewerId)) {
                throw ApiException.notFound("Partie");
            }
            if (session.getQuiz().getStatus() != QuizStatus.FINISHED) {
                throw new ApiException(ErrorCode.GAME_REVIEW_LOCKED);
            }
        }

        List<GameSessionQuestion> draws =
                gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(sessionId);
        Map<Long, Question> questions = loadQuestions(draws);
        Map<Long, GameAnswer> answers = gameAnswerRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(GameAnswer::getQuestionId, Function.identity()));

        List<GameReviewResponse.ReviewItem> items = new ArrayList<>();
        for (GameSessionQuestion draw : draws) {
            Question question = questions.get(draw.getQuestion().getId());
            GameAnswer answer = answers.get(question.getId());

            items.add(new GameReviewResponse.ReviewItem(
                    draw.getPosition(),
                    question.getId(),
                    question.getText(),
                    question.getMedia() == null ? null : question.getMedia().getUrl(),
                    question.getExplanation(),
                    question.getChoices().stream()
                            .map(choice -> new GameReviewResponse.ReviewChoice(
                                    choice.getId(),
                                    choice.getText(),
                                    choice.getMedia() == null ? null : choice.getMedia().getUrl()))
                            .toList(),
                    draw.getCorrectChoiceId(),
                    answer == null ? null : answer.getChoiceId(),
                    answer != null && answer.isCorrect(),
                    answer == null ? null : answer.getElapsedMs()));
        }

        return new GameReviewResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getStatus(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                items);
    }

    // --- Administration et maintenance ---------------------------------------

    /**
     * Invalidation manuelle d'une partie, typiquement apres revue d'un
     * signalement. Impossible une fois le classement fige : les gains sont deja
     * verses.
     *
     * <p>Ordre des verrous : partie puis quiz (partage), comme au submit. Le
     * verrou partage fait attendre le calcul du classement, qui ne peut donc pas
     * payer une partie en train d'etre invalidee.
     */
    @Transactional
    public void invalidateByAdmin(Long actorId, Long sessionId, String reason) {
        GameSession session = gameSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> ApiException.notFound("Partie"));

        Quiz quiz = quizRepository.findByIdForShare(session.getQuiz().getId())
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (quiz.getStatus() == QuizStatus.FINISHED) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Le classement de ce quiz est deja fige : les gains ont ete verses.");
        }

        if (session.getStatus() != GameSessionStatus.SUBMITTED
                && session.getStatus() != GameSessionStatus.IN_PROGRESS) {
            throw new ApiException(ErrorCode.GAME_SESSION_CLOSED);
        }

        invalidate(session, "Administrateur : " + reason, Instant.now(), actorId);
    }

    /** Clot les parties dont l'echeance est passee sans soumission. */
    @Transactional
    public int expireOverdue() {
        return gameSessionRepository.expireOverdue(Instant.now());
    }

    /**
     * Statistiques de questions, ecrites une fois la partie validee en base (voir
     * {@link QuestionStatsRecorder}). Un echec est journalise sans remonter : la
     * partie est deja enregistree, le joueur ne doit pas recevoir d'erreur.
     */
    private void recordStatsAfterCommit(List<Long> served, List<Long> answeredCorrectly) {
        List<Long> servedCopy = List.copyOf(served);
        List<Long> correctCopy = List.copyOf(answeredCorrectly);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    questionStatsRecorder.record(servedCopy, correctCopy);
                } catch (RuntimeException e) {
                    log.warn("Statistiques de questions non mises a jour", e);
                }
            }
        });
    }

    private Map<Long, Question> loadQuestions(List<GameSessionQuestion> draws) {
        List<Long> ids = draws.stream().map(draw -> draw.getQuestion().getId()).toList();
        Map<Long, Question> questions = new HashMap<>();
        for (Question question : questionRepository.findWithChoicesByIdIn(ids)) {
            questions.put(question.getId(), question);
        }
        return questions;
    }
}
