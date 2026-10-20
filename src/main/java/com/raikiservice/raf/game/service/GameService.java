package com.raikiservice.raf.game.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.async.AfterTransactionExecutor;
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
import com.raikiservice.raf.game.web.dto.AnswerQuestionDto;
import com.raikiservice.raf.game.web.dto.GameOutcomeResponse;
import com.raikiservice.raf.game.web.dto.GameResultResponse;
import com.raikiservice.raf.game.web.dto.GameReviewResponse;
import com.raikiservice.raf.game.web.dto.GameStartResponse;
import com.raikiservice.raf.game.web.dto.GameTurnResponse;
import com.raikiservice.raf.game.web.dto.SubmitGameDto;
import com.raikiservice.raf.game.web.dto.SubmitGameDto.AnswerDto;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.QuizQuestionPoolRepository;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deroule d'une partie, selon le modele de revelation differee : le corrige ne
 * quitte jamais le serveur pendant la partie, et le detail des reponses n'est
 * accessible qu'une fois le quiz termine.
 *
 * <p>Chaque question a son propre chrono. Le quiz choisit le deroulement (voir
 * {@link PlayMode}) :
 * <ul>
 *   <li>BATCH : le tirage complet part au demarrage, le joueur joue hors-ligne
 *       et renvoie toutes ses reponses d'un bloc. Le serveur controle a
 *       posteriori la coherence des temps declares.
 *   <li>SEQUENTIAL : une question a la fois. Le serveur horodate l'envoi de
 *       chaque question et refuse une reponse arrivee hors delai : le joueur ne
 *       voit jamais une question avant son tour.
 * </ul>
 *
 * <p>Dans les deux cas, le score est recalcule ici a partir du tirage fige : la
 * valeur que pourrait annoncer le client n'existe meme pas dans l'API.
 *
 * <p>Mode de jeu ({@link GameMode}) : en GROUP, une partie consomme un ticket et
 * compte pour le classement du quiz. En SOLO, elle est payee en credits a son
 * lancement, un sans-faute rapporte aussitot le prix du quiz, et un joueur ne
 * joue qu'une partie par sujet et par jour.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private static final long DEFAULT_GRACE_SECONDS = 15L;
    private static final long DEFAULT_QUESTION_GRACE_MS = 1_500L;
    private static final long DEFAULT_MIN_ANSWER_MS = 400L;
    private static final long DEFAULT_FAST_ANSWER_FLAG_COUNT = 5L;

    /**
     * Tolerance sur les temps declares : le chrono du client et l'arrondi de son
     * affichage ne sont pas a la milliseconde pres. Une seconde ne change rien a
     * la triche possible mais evite d'invalider un joueur honnete.
     */
    private static final long CLOCK_TOLERANCE_MS = 1_000L;

    /** Le "jour" de la limite solo est le jour civil des joueurs. */
    private static final ZoneId PLAY_DAY_ZONE = ZoneId.of("America/Port-au-Prince");

    private final SecureRandom random = new SecureRandom();

    private final GameSessionRepository gameSessionRepository;
    private final GameSessionQuestionRepository gameSessionQuestionRepository;
    private final GameAnswerRepository gameAnswerRepository;
    private final QuizRepository quizRepository;
    private final QuizQuestionPoolRepository quizQuestionPoolRepository;
    private final TicketRepository ticketRepository;
    private final QuestionRepository questionRepository;
    private final UserRepository userRepository;
    private final QuizService quizService;
    private final WalletService walletService;
    private final QuestionStatsRecorder questionStatsRecorder;
    private final AfterTransactionExecutor afterTransactionExecutor;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    // --- Demarrage ------------------------------------------------------------

    /**
     * Lance une partie, ou reprend celle qui est en cours.
     *
     * <p>La reprise est essentielle : un rechargement de page ne doit pas bruler
     * un second ticket. Le joueur retrouve le meme tirage et le chrono la ou il
     * en etait. La reprise ne modifie rien : en SEQUENTIAL, une question expiree
     * pendant l'absence est renvoyee avec un temps restant nul, et c'est la
     * reponse vide du client qui fait passer a la suivante.
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

        if (quiz.isSolo()) {
            return startSolo(quiz, userId, now, ip, userAgent);
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

        ticket.setStatus(TicketStatus.USED);
        ticket.setUsedAt(now);

        GameSession session = newSession(quiz, ticket.getUser(), GameMode.GROUP, now, ip, userAgent);
        session.setTicket(ticket);
        return deal(quiz, session, now);
    }

    /**
     * Lance une partie solo, payee sur le solde CREDIT.
     *
     * <p>Une partie par joueur, par sujet et par jour. La cle d'idempotence du
     * debit porte exactement cette regle ({@code solo:joueur:sujet:jour}) : deux
     * clics simultanes se serialisent sur le verrou du portefeuille, et le second
     * retrouve le debit du premier au lieu de payer une seconde fois. La
     * contrainte unique sur la partie double ce garde-fou en base.
     *
     * <p>Ordre des verrous : quiz (partage) puis portefeuille, comme a l'achat
     * d'un ticket.
     */
    private GameStartResponse startSolo(Quiz quiz, Long userId, Instant now, String ip,
            String userAgent) {

        Category subject = quiz.getSelectedCategory();
        if (!subject.isAvailableFor(GameMode.SOLO)) {
            throw new ApiException(ErrorCode.GAME_NOT_PLAYABLE,
                    "Ce sujet n'est plus propose en solo.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
        // Comme a l'achat d'un ticket : on bloque l'engagement d'argent, pas l'acces.
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verifiez votre adresse email avant de jouer en solo.");
        }

        LocalDate day = LocalDate.ofInstant(now, PLAY_DAY_ZONE);
        if (gameSessionRepository.existsByUserIdAndCategoryIdAndSoloDay(userId, subject.getId(), day)) {
            throw new ApiException(ErrorCode.SOLO_ALREADY_PLAYED_TODAY);
        }

        WalletTransaction entry = walletService.debit(
                userId,
                BalanceType.CREDIT,
                quiz.getTicketPriceCredits(),
                WalletTransactionType.SOLO_ENTRY,
                "GameSession",
                null,
                "solo:" + userId + ":" + subject.getId() + ":" + day,
                "Partie solo : " + quiz.getTitle());

        // Debit deja present : une requete concurrente a paye et cree la partie
        // pendant que celle-ci attendait le verrou du portefeuille (lecture a jour
        // sous READ COMMITTED). On la reprend si elle est encore ouverte.
        Optional<GameSession> concurrent = gameSessionRepository.findByEntryTransactionId(entry.getId());
        if (concurrent.isPresent()) {
            return concurrent
                    .filter(session -> session.isOpenAt(now))
                    .map(session -> view(session, true, now))
                    .orElseThrow(() -> new ApiException(ErrorCode.SOLO_ALREADY_PLAYED_TODAY));
        }

        GameSession session = newSession(quiz, user, GameMode.SOLO, now, ip, userAgent);
        session.setSoloDay(day);
        session.setEntryPriceCredits(quiz.getTicketPriceCredits());
        session.setEntryTransactionId(entry.getId());
        session.setPrizeHtg(quiz.getSoloPrizeHtg());

        GameStartResponse response = deal(quiz, session, now);
        entry.setReferenceId(String.valueOf(session.getId()));
        return response;
    }

    private GameSession newSession(Quiz quiz, User user, GameMode mode, Instant now, String ip,
            String userAgent) {

        GameSession session = new GameSession();
        session.setQuiz(quiz);
        session.setUser(user);
        session.setGameMode(mode);
        session.setCategoryId(quiz.getSelectedCategory() == null ? null
                : quiz.getSelectedCategory().getId());
        session.setStatus(GameSessionStatus.IN_PROGRESS);
        session.setPlayMode(quiz.getPlayMode());
        session.setQuestionDurationSeconds(quiz.getQuestionDurationSeconds());
        session.setPointsPerCorrect(quiz.getPointsPerCorrect());
        session.setStartedAt(now);
        session.setIp(ip);
        session.setUserAgent(userAgent);
        return session;
    }

    /** Tire les questions de la partie, l'enregistre et renvoie la vue du joueur. */
    private GameStartResponse deal(Quiz quiz, GameSession session, Instant now) {
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

        PlayMode mode = session.getPlayMode();
        int perQuestion = session.getQuestionDurationSeconds();
        session.setDurationSeconds(perQuestion * picked.size());
        session.setServerDeadlineAt(deadline(mode, now, picked.size(), perQuestion));
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

        // SEQUENTIAL : la premiere question part avec la reponse, son chrono aussi.
        GameSessionQuestion pending = null;
        if (mode == PlayMode.SEQUENTIAL) {
            pending = draws.get(0);
            pending.setServedAt(now);
        }

        recordStatsAfterCommit(picked, List.of());

        log.debug("Partie {} lancee ({}, {}) : joueur {}, quiz {}, {} question(s)",
                session.getId(), session.getGameMode(), mode, session.getUser().getId(),
                quiz.getId(), draws.size());

        return view(session, draws, questions, pending, false, now);
    }

    /**
     * Echeance au-dela de laquelle la partie est close.
     *
     * <p>SEQUENTIAL : chaque question peut consommer son chrono plus la marge par
     * question avant que la suivante ne parte. La marge reseau finale couvre le
     * temps de traitement de la derniere reponse.
     */
    private Instant deadline(PlayMode mode, Instant start, int questionCount, int perQuestionSeconds) {
        long grace = settingsService.getLong(
                SettingKeys.GAME_NETWORK_GRACE_SECONDS, DEFAULT_GRACE_SECONDS);
        long playMs = questionCount * perQuestionSeconds * 1_000L;
        if (mode == PlayMode.SEQUENTIAL) {
            playMs += questionCount * questionGraceMs();
        }
        return start.plusMillis(playMs).plusSeconds(grace);
    }

    private GameStartResponse view(GameSession session, boolean resumed, Instant now) {
        List<GameSessionQuestion> draws =
                gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(session.getId());

        if (session.getPlayMode() == PlayMode.BATCH) {
            return view(session, draws, loadQuestions(draws), null, resumed, now);
        }

        GameSessionQuestion pending =
                firstUnanswered(draws, gameAnswerRepository.findBySessionId(session.getId()));
        Map<Long, Question> questions = pending == null ? Map.of() : loadQuestions(List.of(pending));
        return view(session, draws, questions, pending, resumed, now);
    }

    /**
     * BATCH : tout le tirage. SEQUENTIAL : la seule question en cours
     * ({@code pending}), les autres ne sortent pas du serveur.
     */
    private GameStartResponse view(GameSession session, List<GameSessionQuestion> draws,
            Map<Long, Question> questions, GameSessionQuestion pending, boolean resumed,
            Instant now) {

        List<GameStartResponse.QuestionView> views = List.of();
        GameStartResponse.CurrentQuestion current = null;

        if (session.getPlayMode() == PlayMode.BATCH) {
            views = draws.stream()
                    .map(draw -> questionView(session, draw, questions))
                    .toList();
        } else if (pending != null) {
            current = currentQuestion(session, pending, questions, now);
        }

        long remaining = Math.max(0, Duration.between(now, session.playEndsAt()).toMillis());

        return new GameStartResponse(
                session.getId(),
                session.getQuiz().getId(),
                resumed,
                session.getPlayMode(),
                session.getQuestionDurationSeconds(),
                draws.size(),
                session.getDurationSeconds(),
                session.getStartedAt(),
                session.playEndsAt(),
                remaining,
                views,
                current);
    }

    private GameStartResponse.CurrentQuestion currentQuestion(GameSession session,
            GameSessionQuestion draw, Map<Long, Question> questions, Instant now) {

        Instant endsAt = draw.getServedAt().plusMillis(session.questionDurationMs());
        long remaining = Math.max(0, Duration.between(now, endsAt).toMillis());
        return new GameStartResponse.CurrentQuestion(
                questionView(session, draw, questions), endsAt, remaining);
    }

    private GameStartResponse.QuestionView questionView(GameSession session,
            GameSessionQuestion draw, Map<Long, Question> questions) {

        Question question = questions.get(draw.getQuestion().getId());

        List<GameStartResponse.ChoiceView> choices = shuffledChoices(session, question).stream()
                .map(choice -> new GameStartResponse.ChoiceView(
                        choice.getId(),
                        choice.getText(),
                        choice.getMedia() == null ? null : choice.getMedia().getUrl()))
                .toList();

        return new GameStartResponse.QuestionView(
                question.getId(),
                draw.getPosition(),
                question.getText(),
                question.getMedia() == null ? null : question.getMedia().getUrl(),
                question.getType(),
                choices);
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

    // --- Soumission groupee (BATCH) ---------------------------------------------

    /**
     * Corrige une partie BATCH.
     *
     * <p>Toute incoherence invalide la partie au lieu de lever une erreur : une
     * exception annulerait la transaction, et donc l'invalidation elle-meme, et le
     * joueur pourrait resoumettre jusqu'a passer les controles.
     *
     * <p>Le quiz n'est jamais charge ici (mode, chrono et bareme sont copies dans
     * la partie). C'est ce qui garantit que le verrou pris par
     * {@link QuizService#triggerClosing} est la premiere lecture du quiz dans la
     * transaction, et donc qu'il lit un etat a jour.
     */
    @Transactional
    public GameResultResponse submit(Long userId, SubmitGameDto request) {
        GameSession session = lockOwned(userId, request.sessionId());

        if (session.getPlayMode() != PlayMode.BATCH) {
            throw new ApiException(ErrorCode.GAME_WRONG_MODE,
                    "Cette partie se joue question par question.");
        }

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

        List<GameAnswer> entries = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            AnswerDto answer = answers.get(i);
            entries.add(record(session, draws.get(i), answer.choiceId(), answer.elapsedMs()));
        }

        session.setClientDurationMs(request.clientDurationMs());
        complete(session, draws.size(), entries, now);

        return result(session, userId);
    }

    /**
     * Controles de coherence. Renvoie le motif de la premiere violation, ou null.
     *
     * <p>Le jeu interdit de sauter une question : la reponse n doit porter sur la
     * question n du tirage. Ce seul controle couvre a la fois les questions hors
     * tirage, les doublons et le desordre.
     *
     * <p>Chrono par question : la question n s'affiche des la reponse (ou
     * l'expiration) de la question n-1, l'ecart entre deux temps declares ne peut
     * donc pas depasser le chrono. Ces temps restent declares par le client : le
     * serveur ne borne reellement que la duree totale.
     */
    private String findViolation(GameSession session, List<GameSessionQuestion> draws,
            List<AnswerDto> answers, Map<Long, Question> questions, Instant now) {

        if (answers.size() > draws.size()) {
            return "Plus de reponses (" + answers.size() + ") que de questions servies ("
                    + draws.size() + ").";
        }

        long maxElapsed = session.getDurationSeconds() * 1_000L + CLOCK_TOLERANCE_MS;
        long maxPerQuestion = session.questionDurationMs() + CLOCK_TOLERANCE_MS;
        long previous = 0;

        for (int i = 0; i < answers.size(); i++) {
            AnswerDto answer = answers.get(i);
            Long expectedQuestionId = draws.get(i).getQuestion().getId();

            if (!expectedQuestionId.equals(answer.questionId())) {
                return "Reponse " + (i + 1) + " : question " + answer.questionId()
                        + " au lieu de " + expectedQuestionId + ".";
            }

            if (answer.choiceId() != null
                    && !belongsTo(questions.get(expectedQuestionId), answer.choiceId())) {
                return "Reponse " + (i + 1) + " : le choix " + answer.choiceId()
                        + " n'appartient pas a la question.";
            }

            if (answer.elapsedMs() < previous) {
                return "Reponse " + (i + 1) + " : temps ecoule decroissant.";
            }
            if (answer.elapsedMs() - previous > maxPerQuestion) {
                return "Reponse " + (i + 1) + " : " + (answer.elapsedMs() - previous)
                        + " ms sur la question pour un chrono de "
                        + session.getQuestionDurationSeconds() + " s.";
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

    // --- Question par question (SEQUENTIAL) -------------------------------------

    /**
     * Enregistre la reponse a la question en cours et sert la suivante.
     *
     * <p>Le chrono de la question part de son envoi ({@code servedAt}). Une
     * reponse arrivee apres le chrono et la marge par question est enregistree
     * sans choix : la question compte comme fausse. Une reponse vide (passer, ou
     * chrono expire cote client) fait simplement avancer.
     *
     * <p>Une reponse sur une autre question que celle en cours n'enregistre rien
     * et renvoie l'etat courant : c'est le cas d'un envoi rejoue apres une coupure
     * reseau. Un choix etranger a la question, lui, ne peut venir que d'un client
     * trafique : la partie est invalidee, comme au submit groupe.
     *
     * <p>Meme ordre des verrous et meme garantie que {@link #submit} : la partie
     * est verrouillee, le quiz n'est pas lu avant un eventuel declenchement de
     * la cloture.
     */
    @Transactional
    public GameTurnResponse answer(Long userId, Long sessionId, AnswerQuestionDto request) {
        GameSession session = lockOwned(userId, sessionId);

        if (session.getPlayMode() != PlayMode.SEQUENTIAL) {
            throw new ApiException(ErrorCode.GAME_WRONG_MODE,
                    "Cette partie se soumet en une fois, a la fin.");
        }

        List<GameSessionQuestion> draws =
                gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(sessionId);
        List<GameAnswer> answers = new ArrayList<>(gameAnswerRepository.findBySessionId(sessionId));

        switch (session.getStatus()) {
            case SUBMITTED, INVALIDATED -> {
                // Rejeu de la derniere reponse : la partie est deja corrigee.
                return finished(session, userId, draws, answers, request);
            }
            case EXPIRED -> throw new ApiException(ErrorCode.GAME_TIME_EXPIRED);
            case IN_PROGRESS -> {
                // Cas nominal, traite ci-dessous.
            }
        }

        Instant now = Instant.now();
        GameSessionQuestion pending = firstUnanswered(draws, answers);

        if (pending == null || now.isAfter(session.getServerDeadlineAt())) {
            // Echeance depassee : les reponses deja donnees comptent, rien de plus.
            complete(session, draws.size(), answers, now);
            return finished(session, userId, draws, answers, request);
        }

        if (!pending.getQuestion().getId().equals(request.questionId())) {
            return turn(session, draws, answers, pending, request, now);
        }

        Long choiceId = request.choiceId();
        if (choiceId != null) {
            Question question = questionRepository.findWithChoicesById(request.questionId())
                    .orElseThrow(() -> ApiException.notFound("Question"));
            if (!belongsTo(question, choiceId)) {
                String violation = "Question " + pending.getPosition() + " : le choix " + choiceId
                        + " n'appartient pas a la question.";
                invalidate(session, violation, now, null);
                log.warn("Partie {} invalidee : {}", session.getId(), violation);
                return finished(session, userId, draws, answers, request);
            }
        }

        long spentMs = Duration.between(pending.getServedAt(), now).toMillis();
        boolean late = spentMs > session.questionDurationMs() + questionGraceMs();

        long elapsedMs = Duration.between(session.getStartedAt(), now).toMillis();
        answers.add(record(session, pending, late ? null : choiceId, elapsedMs));

        int nextIndex = draws.indexOf(pending) + 1;
        if (nextIndex == draws.size()) {
            complete(session, draws.size(), answers, now);
            return finished(session, userId, draws, answers, request);
        }

        GameSessionQuestion next = draws.get(nextIndex);
        next.setServedAt(now);
        return turn(session, draws, answers, next, request, now);
    }

    private GameTurnResponse turn(GameSession session, List<GameSessionQuestion> draws,
            List<GameAnswer> answers, GameSessionQuestion pending, AnswerQuestionDto request,
            Instant now) {

        return new GameTurnResponse(
                session.getId(),
                accepted(answers, request),
                answers.size(),
                draws.size(),
                currentQuestion(session, pending, loadQuestions(List.of(pending)), now),
                null);
    }

    private GameTurnResponse finished(GameSession session, Long userId,
            List<GameSessionQuestion> draws, List<GameAnswer> answers,
            AnswerQuestionDto request) {

        return new GameTurnResponse(
                session.getId(),
                accepted(answers, request),
                answers.size(),
                draws.size(),
                null,
                result(session, userId));
    }

    /**
     * La reponse envoyee est-elle celle qui est enregistree ? Vrai aussi pour un
     * envoi rejoue, faux pour une reponse hors delai (enregistree sans choix).
     */
    private static boolean accepted(List<GameAnswer> answers, AnswerQuestionDto request) {
        return answers.stream()
                .filter(answer -> answer.getQuestionId().equals(request.questionId()))
                .anyMatch(answer -> Objects.equals(answer.getChoiceId(), request.choiceId()));
    }

    private static GameSessionQuestion firstUnanswered(List<GameSessionQuestion> draws,
            List<GameAnswer> answers) {

        Set<Long> answered = answers.stream()
                .map(GameAnswer::getQuestionId)
                .collect(Collectors.toSet());
        return draws.stream()
                .filter(draw -> !answered.contains(draw.getQuestion().getId()))
                .findFirst()
                .orElse(null);
    }

    // --- Correction ---------------------------------------------------------------

    private GameAnswer record(GameSession session, GameSessionQuestion draw, Long choiceId,
            long elapsedMs) {

        boolean correct = draw.getCorrectChoiceId().equals(choiceId);

        GameAnswer entry = new GameAnswer();
        entry.setSession(session);
        entry.setQuestionId(draw.getQuestion().getId());
        entry.setChoiceId(choiceId);
        entry.setCorrect(correct);
        entry.setElapsedMs(elapsedMs);
        entry.setPointsAwarded(correct ? session.getPointsPerCorrect() : 0);
        return gameAnswerRepository.save(entry);
    }

    /**
     * Score final a partir des reponses enregistrees. Puis, en GROUP,
     * declenchement de la cloture du quiz si ce score depasse le seuil ; en SOLO,
     * versement du prix en cas de sans-faute. Commun aux deux deroulements.
     */
    private void complete(GameSession session, int totalQuestions, List<GameAnswer> answers,
            Instant now) {
        List<Long> correctQuestionIds = answers.stream()
                .filter(GameAnswer::isCorrect)
                .map(GameAnswer::getQuestionId)
                .toList();
        int answeredCount = (int) answers.stream()
                .filter(answer -> answer.getChoiceId() != null)
                .count();
        int score = correctQuestionIds.size() * session.getPointsPerCorrect();

        session.setStatus(GameSessionStatus.SUBMITTED);
        session.setSubmittedAt(now);
        session.setScore(score);
        session.setCorrectCount(correctQuestionIds.size());
        session.setAnsweredCount(answeredCount);

        flagFastAnswers(session, answers);
        recordStatsAfterCommit(List.of(), correctQuestionIds);

        if (session.isSolo()) {
            if (correctQuestionIds.size() == totalQuestions) {
                payPrize(session);
            }
            return;
        }

        // Projection scalaire : ne charge pas le quiz (voir la javadoc de submit).
        Long quizId = session.getQuiz().getId();
        quizRepository.findMinScoreThreshold(quizId, QuizStatus.RUNNING)
                .filter(threshold -> score > threshold)
                .ifPresent(threshold -> quizService.triggerClosing(quizId, score));
    }

    /**
     * Sans-faute en solo : le prix est verse sur le solde des gains, retirable.
     * Cle d'idempotence par partie : un rejeu ne paie jamais deux fois. Le joueur
     * est prevenu apres le commit, comme pour un gain au classement.
     *
     * <p>Charger le quiz (pour son titre) est sans risque ici : la regle "le quiz
     * n'est pas lu avant triggerClosing" ne concerne que le mode GROUP.
     */
    private void payPrize(GameSession session) {
        WalletTransaction prize = walletService.credit(
                session.getUser().getId(),
                BalanceType.WINNINGS,
                session.getPrizeHtg(),
                WalletTransactionType.PRIZE,
                "GameSession",
                String.valueOf(session.getId()),
                "solo-prize:session:" + session.getId(),
                "Sans-faute en solo (partie " + session.getId() + ")");

        session.setWon(true);
        session.setPrizeTransactionId(prize.getId());

        notificationService.notifyUser(session.getUser().getId(),
                NotificationMessages.soloPrizeWon(session.getQuiz(), session.getId(),
                        session.getPrizeHtg()));
        log.info("Partie solo {} : sans-faute, {} HTG verses", session.getId(),
                session.getPrizeHtg());
    }

    /**
     * Temps de reaction humainement improbables. Simple signalement pour revue :
     * un joueur rapide et sur de lui n'est pas un tricheur, on ne sanctionne donc
     * pas automatiquement. Une question laissee sans reponse n'est pas comptee.
     */
    private void flagFastAnswers(GameSession session, List<GameAnswer> answers) {
        long minAnswerMs = settingsService.getLong(
                SettingKeys.GAME_MIN_ANSWER_MS, DEFAULT_MIN_ANSWER_MS);
        long flagCount = settingsService.getLong(
                SettingKeys.GAME_FAST_ANSWER_FLAG_COUNT, DEFAULT_FAST_ANSWER_FLAG_COUNT);

        List<GameAnswer> chronological = answers.stream()
                .sorted(Comparator.comparingLong(GameAnswer::getElapsedMs))
                .toList();

        int fast = 0;
        long previous = 0;
        for (GameAnswer answer : chronological) {
            if (answer.getChoiceId() != null && answer.getElapsedMs() - previous < minAnswerMs) {
                fast++;
            }
            previous = answer.getElapsedMs();
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

    /** Sujets deja joues en solo aujourd'hui : plus jouables avant demain. */
    @Transactional(readOnly = true)
    public List<Long> soloSubjectsPlayedToday(Long userId) {
        return gameSessionRepository.findSoloCategoryIds(userId,
                LocalDate.now(PLAY_DAY_ZONE));
    }

    /**
     * Corrige du joueur : juste, fausse ou sans reponse, question par question.
     *
     * <p>Jamais l'enonce ni la bonne reponse : les questions restent dans le
     * vivier, et un corrige partage deviendrait l'antiseche des suivants (et, en
     * solo, de quiconque rejoue le sujet). Uniquement une fois le quiz termine
     * (groupe) ou ferme (solo).
     */
    @Transactional(readOnly = true)
    public GameOutcomeResponse outcome(Long viewerId, Long sessionId) {
        GameSession session = gameSessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Partie"));

        if (!session.getUser().getId().equals(viewerId)) {
            throw ApiException.notFound("Partie");
        }
        if (session.getQuiz().getStatus() != QuizStatus.FINISHED) {
            throw new ApiException(ErrorCode.GAME_REVIEW_LOCKED);
        }

        Map<Long, GameAnswer> answers = gameAnswerRepository.findBySessionId(sessionId).stream()
                .collect(Collectors.toMap(GameAnswer::getQuestionId, Function.identity()));

        List<GameOutcomeResponse.Outcome> items = new ArrayList<>();
        for (GameSessionQuestion draw : gameSessionQuestionRepository.findBySessionIdOrderByPositionAsc(sessionId)) {
            GameAnswer answer = answers.get(draw.getQuestion().getId());
            GameOutcomeResponse.Result result = answer == null || answer.getChoiceId() == null
                    ? GameOutcomeResponse.Result.UNANSWERED
                    : answer.isCorrect() ? GameOutcomeResponse.Result.CORRECT : GameOutcomeResponse.Result.WRONG;
            items.add(new GameOutcomeResponse.Outcome(draw.getPosition(), result));
        }

        return new GameOutcomeResponse(
                session.getId(),
                session.getQuiz().getId(),
                session.getStatus(),
                session.getScore(),
                session.getCorrectCount(),
                session.getAnsweredCount(),
                items);
    }

    /**
     * Corrige detaille (enonces, propositions, bonne reponse, temps), reserve au
     * back-office : il sert a juger une partie signalee.
     */
    @Transactional(readOnly = true)
    public GameReviewResponse review(Long sessionId) {
        GameSession session = gameSessionRepository.findById(sessionId)
                .orElseThrow(() -> ApiException.notFound("Partie"));

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

        // En solo, le prix part a la fin de la partie : il n'y a plus rien a
        // retenir. Une reprise passerait par un ajustement trace.
        if (session.isWon()) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Le prix de ce sans-faute a deja ete verse.");
        }

        invalidate(session, "Administrateur : " + reason, Instant.now(), actorId);
    }

    /**
     * Clot les parties BATCH dont l'echeance est passee sans soumission : rien
     * n'a ete enregistre, elles ne comptent pas.
     */
    @Transactional
    public int expireOverdue() {
        return gameSessionRepository.expireOverdue(PlayMode.BATCH, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<Long> findOverdueSequential() {
        return gameSessionRepository.findOverdueIds(PlayMode.SEQUENTIAL, Instant.now());
    }

    /**
     * Clot une partie SEQUENTIAL abandonnee en cours de route (coupure, onglet
     * ferme). Ses reponses ont ete validees une a une par le serveur : elles
     * comptent, les questions restantes valent zero.
     *
     * @return false si la partie a ete close entre-temps
     */
    @Transactional
    public boolean completeOverdue(Long sessionId) {
        GameSession session = gameSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        Instant now = Instant.now();
        if (session == null || session.getStatus() != GameSessionStatus.IN_PROGRESS
                || !now.isAfter(session.getServerDeadlineAt())) {
            return false;
        }

        int total = gameSessionQuestionRepository.countBySessionId(sessionId);
        complete(session, total, new ArrayList<>(gameAnswerRepository.findBySessionId(sessionId)), now);
        return true;
    }

    // --- Outils -----------------------------------------------------------------

    private GameSession lockOwned(Long userId, Long sessionId) {
        GameSession session = gameSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> ApiException.notFound("Partie"));

        if (!session.getUser().getId().equals(userId)) {
            throw ApiException.notFound("Partie");
        }
        return session;
    }

    private long questionGraceMs() {
        return settingsService.getLong(SettingKeys.GAME_QUESTION_GRACE_MS, DEFAULT_QUESTION_GRACE_MS);
    }

    private static boolean belongsTo(Question question, Long choiceId) {
        return question.getChoices().stream()
                .map(QuestionChoice::getId)
                .anyMatch(choiceId::equals);
    }

    /**
     * Statistiques de questions, ecrites une fois la partie validee en base (voir
     * {@link QuestionStatsRecorder}), sur un thread a part : la connexion de la
     * partie est rendue avant que les compteurs n'en demandent une. Un echec est
     * journalise sans remonter : la partie est deja enregistree.
     */
    private void recordStatsAfterCommit(List<Long> served, List<Long> answeredCorrectly) {
        List<Long> servedCopy = List.copyOf(served);
        List<Long> correctCopy = List.copyOf(answeredCorrectly);

        afterTransactionExecutor.afterCommit("statistiques de questions",
                () -> questionStatsRecorder.record(servedCopy, correctCopy));
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
