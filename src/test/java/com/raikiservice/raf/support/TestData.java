package com.raikiservice.raf.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.quiz.domain.PlayMode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.TopicMode;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.RewardService;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.QuizDto;
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.quiz.web.dto.SoloQuizDto;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.repository.WalletRepository;
import com.raikiservice.raf.wallet.service.AdjustmentService;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fabrique de donnees de test, en passant par les vrais services : un quiz cree
 * ici a franchi les memes verrous de publication qu'en production.
 *
 * <p>Tous les identifiants (emails, telephones, noms) sont uniques par
 * lancement et par appel.
 */
public class TestData {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6);

    public static final int TICKET_PRICE = 10;
    public static final long FIRST_PRIZE = 100;
    public static final long SECOND_PRIZE = 50;
    public static final int SOLO_ENTRY = 5;
    public static final long SOLO_PRIZE = 40;

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private AdjustmentService adjustmentService;
    @Autowired private CategoryService categoryService;
    @Autowired private QuestionService questionService;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private QuizService quizService;
    @Autowired private RewardService rewardService;
    @Autowired private TicketService ticketService;

    // --- Comptes ----------------------------------------------------------------

    public User player() {
        return user(RoleName.PLAYER, true);
    }

    public User player(boolean phoneVerified) {
        return user(RoleName.PLAYER, phoneVerified);
    }

    public User admin() {
        return user(RoleName.ADMIN, true);
    }

    public User moderator() {
        return user(RoleName.MODERATOR, true);
    }

    private User user(RoleName role, boolean phoneVerified) {
        long n = SEQUENCE.incrementAndGet();
        User user = new User();
        user.setEmail("t" + RUN + "-" + n + "@raf.test");
        // +509 + 8 chiffres : unique par lancement grace au prefixe aleatoire.
        user.setPhone("+509" + String.format("%08d",
                (Math.abs(RUN.hashCode()) % 9000 + 1000) * 10000L + n % 10000));
        user.setPasswordHash("$2a$12$test.hash.not.used.for.login.in.tests.................");
        user.setFirstName("Joueur" + n);
        user.setLastName("Test");
        user.setEmailVerifiedAt(Instant.now());
        user.setPhoneVerifiedAt(phoneVerified ? Instant.now() : null);
        user.getRoles().add(roleRepository.findByName(role).orElseThrow());
        return userRepository.save(user);
    }

    // --- Argent -----------------------------------------------------------------

    /** Credite (ou debite si negatif) par un ajustement, la seule voie hors operation metier. */
    public void credit(Long userId, BalanceType balance, long credits) {
        adjustmentService.adjust(null, userId, balance, credits, "donnees de test");
    }

    public long balance(Long userId, BalanceType balance) {
        return walletRepository.findByUserId(userId).orElseThrow().balanceOf(balance);
    }

    // --- Questions --------------------------------------------------------------

    /** Categorie avec {@code count} questions publiees, bonne reponse en position variable. */
    public Long categoryWithQuestions(int count) {
        long n = SEQUENCE.incrementAndGet();
        Long categoryId = categoryService.create(null, "Categorie " + RUN + " " + n, null, null, null)
                .id();

        for (int i = 0; i < count; i++) {
            List<ChoiceDto> choices = new ArrayList<>();
            int correct = i % 4;
            for (int c = 0; c < 4; c++) {
                choices.add(new ChoiceDto("Reponse " + c + " de la question " + i, c == correct, null));
            }
            Question question = questionService.createEntity(null, categoryId,
                    "Question " + i + " de la categorie " + n + " ?", QuestionType.SINGLE_CHOICE,
                    Difficulty.MEDIUM, "Explication " + i, null, choices);
            questionService.changeStatus(null, question.getId(), QuestionStatus.PUBLISHED);
        }
        return categoryId;
    }

    public Long correctChoice(Long questionId) {
        return questionRepository.findWithChoicesById(questionId).orElseThrow()
                .correctChoice().getId();
    }

    public Long wrongChoice(Long questionId) {
        return questionRepository.findWithChoicesById(questionId).orElseThrow()
                .getChoices().stream()
                .filter(choice -> !choice.isCorrect())
                .map(QuestionChoice::getId)
                .findFirst().orElseThrow();
    }

    // --- Quiz -------------------------------------------------------------------

    /** Chrono par question des quiz de test : large, pour ne pas dependre de la machine. */
    public static final int QUESTION_SECONDS = 20;

    /**
     * Quiz publie (mode ADMIN, deroulement BATCH), recompenses (1er : 100,
     * 2e : 50), sans tickets vendus.
     *
     * @param minPlayers seuil de joueurs distincts declenchant la programmation
     */
    public Long publishedQuiz(Long categoryId, int questionsPerGame, int minScoreThreshold,
            int minPlayers, Integer maxTicketsTotal, Integer maxTicketsPerPlayer) {
        return publishedQuiz(PlayMode.BATCH, categoryId, questionsPerGame, minScoreThreshold,
                minPlayers, maxTicketsTotal, maxTicketsPerPlayer);
    }

    public Long publishedQuiz(PlayMode playMode, Long categoryId, int questionsPerGame,
            int minScoreThreshold, int minPlayers, Integer maxTicketsTotal,
            Integer maxTicketsPerPlayer) {
        long n = SEQUENCE.incrementAndGet();
        QuizDto dto = new QuizDto(
                "Quiz " + RUN + " " + n, null, null, TopicMode.ADMIN, categoryId, null,
                TICKET_PRICE, minPlayers, maxTicketsTotal, maxTicketsPerPlayer,
                0, playMode, QUESTION_SECONDS, questionsPerGame, 10, minScoreThreshold, 1);

        Quiz quiz = quizService.create(null, dto);
        rewardService.configure(null, quiz, List.of(
                new RewardTierDto(1, 1, FIRST_PRIZE, "1er"),
                new RewardTierDto(2, 2, SECOND_PRIZE, "2e")));
        quizService.publish(null, quiz.getId(), null);
        return quiz.getId();
    }

    /**
     * Quiz solo ouvert (RUNNING) : partie a {@link #SOLO_ENTRY} credits, sans-faute
     * a {@link #SOLO_PRIZE}.
     */
    public Long soloQuiz(Long categoryId, int questionsPerGame) {
        long n = SEQUENCE.incrementAndGet();
        Quiz quiz = quizService.createSolo(null, new SoloQuizDto(
                "Solo " + RUN + " " + n, null, categoryId, null, SOLO_ENTRY, SOLO_PRIZE,
                questionsPerGame, QUESTION_SECONDS));
        quizService.publish(null, quiz.getId(), null);
        return quiz.getId();
    }

    /**
     * Quiz BATCH en cours (RUNNING) : chaque joueur recoit de quoi acheter
     * {@code ticketsEach} tickets et les achete, puis le quiz est demarre.
     */
    public Long runningQuiz(Long categoryId, int questionsPerGame, int minScoreThreshold,
            int ticketsEach, User... players) {
        return runningQuiz(PlayMode.BATCH, categoryId, questionsPerGame, minScoreThreshold,
                ticketsEach, players);
    }

    public Long runningQuiz(PlayMode playMode, Long categoryId, int questionsPerGame,
            int minScoreThreshold, int ticketsEach, User... players) {
        Long quizId = publishedQuiz(playMode, categoryId, questionsPerGame, minScoreThreshold,
                players.length, null, null);

        for (User player : players) {
            credit(player.getId(), BalanceType.CREDIT, (long) TICKET_PRICE * ticketsEach);
            for (int t = 0; t < ticketsEach; t++) {
                ticketService.purchase(player.getId(), quizId, UUID.randomUUID().toString());
            }
        }
        quizService.start(null, quizId);
        return quizId;
    }
}
