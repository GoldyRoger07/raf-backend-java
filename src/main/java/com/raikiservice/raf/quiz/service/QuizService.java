package com.raikiservice.raf.quiz.service;

import com.raikiservice.raf.game.repository.GameSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.common.web.SearchTerms;
import com.raikiservice.raf.common.web.SortableFields;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.service.CategoryService;
import com.raikiservice.raf.question.service.QuestionService;
import com.raikiservice.raf.notification.service.NotificationMessages;
import com.raikiservice.raf.notification.service.NotificationService;
import com.raikiservice.raf.quiz.domain.DifficultyMix;
import com.raikiservice.raf.quiz.domain.GameMode;
import com.raikiservice.raf.quiz.domain.PlayMode;
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
import com.raikiservice.raf.quiz.web.dto.RewardTierDto;
import com.raikiservice.raf.quiz.web.dto.SoloQuizDto;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
    private final GameSessionRepository gameSessionRepository;
    private final WalletService walletService;
    private final MediaService mediaService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final QuizAccess quizAccess;
    private final PlayerAppService playerAppService;

    @Transactional(readOnly = true)
    public Quiz require(Long id) {
        return quizRepository.findById(id).orElseThrow(() -> ApiException.notFound("Quiz"));
    }

    /** @param mode null : les deux modes */
    @Transactional(readOnly = true)
    public Page<Quiz> byStatus(QuizStatus status, GameMode mode, Pageable pageable) {
        return mode == null
                ? quizRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : quizRepository.findByGameModeAndStatusOrderByCreatedAtDesc(mode, status, pageable);
    }

    /** Quiz visibles d'un joueur : tout sauf les brouillons. */
    @Transactional(readOnly = true)
    /**
     * Quiz montres aux joueurs. Un quiz de groupe reste visible apres sa cloture
     * (classement, corrige) ; un quiz solo, seulement tant qu'il est ouvert.
     */
    public Page<Quiz> visibleToPlayers(GameMode mode, Pageable pageable) {
        return visibleToPlayers(mode, false, null, pageable);
    }

    /**
     * @param includeTests vrai pour le staff et les comptes de test (voir QuizAccess) : quiz de
     *        test et quiz masques compris
     * @param viewerId joueur qui consulte : un quiz masque reste dans sa liste s'il y a un ticket
     */
    public Page<Quiz> visibleToPlayers(GameMode mode, boolean includeTests, Long viewerId, Pageable pageable) {
        List<QuizStatus> statuses = mode == GameMode.SOLO
                ? List.of(QuizStatus.RUNNING)
                : List.of(QuizStatus.REGISTRATION, QuizStatus.SCHEDULED, QuizStatus.RUNNING,
                        QuizStatus.CLOSING, QuizStatus.COMPUTING, QuizStatus.FINISHED);
        // Interrupteur global coupe : plus aucun quiz solo pour les joueurs.
        if (mode == GameMode.SOLO && !includeTests && !playerAppService.soloQuizzesVisible()) {
            return Page.empty(PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        }
        // Quiz solo caches a ce joueur apres un sans-faute (liste jamais vide pour le NOT IN).
        Set<Long> excluded = new HashSet<>(quizAccess.cooldownHiddenQuizIds(viewerId, null));
        excluded.add(-1L);
        return quizRepository.visibleToPlayers(mode, statuses, includeTests, viewerId, excluded,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }

    /**
     * Regle « cache apres un sans-faute » d'un quiz solo, modifiable a tout moment (meme
     * ouvert) : elle ne touche ni aux prix ni aux gains. Les sans-fautes passes comptent.
     *
     * @param minutes delai, ou null pour desactiver
     */
    @Transactional
    public Quiz setPerfectCooldown(Long actorId, Long quizId, Integer minutes) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        assertMode(quiz, GameMode.SOLO);
        Integer previous = quiz.getPerfectCooldownMinutes();
        if (!java.util.Objects.equals(previous, minutes)) {
            quiz.setPerfectCooldownMinutes(minutes);
            auditService.record(actorId, "QUIZ_PERFECT_COOLDOWN_CHANGED", "Quiz", String.valueOf(quizId),
                    String.valueOf(previous), String.valueOf(minutes));
        }
        return quiz;
    }

    /**
     * Repartition par difficulte d'un quiz solo, modifiable a tout moment (meme ouvert) :
     * elle ne touche que les parties lancees ensuite. Un ajustement apres l'ouverture
     * corrige un quiz ou les sans-fautes sont trop frequents, ou trop rares.
     *
     * @param mix repartition, ou null pour revenir au tirage libre
     */
    @Transactional
    public Quiz setDifficultyMix(Long actorId, Long quizId, DifficultyMix mix) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        assertMode(quiz, GameMode.SOLO);
        DifficultyMix previous = quiz.getDifficultyMix();
        if (!java.util.Objects.equals(previous, mix)) {
            quiz.setDifficultyMix(mix);
            auditService.record(actorId, "QUIZ_DIFFICULTY_MIX_CHANGED", "Quiz", String.valueOf(quizId),
                    describe(previous), describe(mix));
        }
        return quiz;
    }

    private static String describe(DifficultyMix mix) {
        return mix == null ? "libre"
                : mix.easyPercent() + "/" + mix.mediumPercent() + "/" + mix.hardPercent();
    }

    /**
     * Masque ou demasque un quiz aux joueurs, quel que soit son statut. Rien d'autre ne
     * change : les tickets et parties en cours sont conserves.
     */
    @Transactional
    public Quiz setHidden(Long actorId, Long quizId, boolean hidden) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        boolean previous = quiz.isHidden();
        if (previous != hidden) {
            quiz.setHidden(hidden);
            auditService.record(actorId, "QUIZ_HIDDEN_CHANGED", "Quiz", String.valueOf(quizId),
                    String.valueOf(previous), String.valueOf(hidden));
        }
        return quiz;
    }

    /**
     * Marque ou demarque un quiz de test. Le marquer est refuse des que de vrais
     * joueurs y ont un ticket ou une partie : il leur deviendrait invisible, et leur
     * argent sortirait des comptes.
     */
    @Transactional
    public Quiz setTest(Long actorId, Long quizId, boolean test) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        if (test && !quiz.isTest() && (ticketRepository.countRealPlayerTickets(quizId) > 0
                || gameSessionRepository.countRealPlayerSessions(quizId) > 0)) {
            throw new ApiException(ErrorCode.QUIZ_HAS_REAL_PLAYERS);
        }
        boolean previous = quiz.isTest();
        quiz.setTest(test);
        auditService.record(actorId, "QUIZ_TEST_FLAG_CHANGED", "Quiz", String.valueOf(quizId),
                String.valueOf(previous), String.valueOf(test));
        return quiz;
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
        assertMode(quiz, GameMode.GROUP);

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
        assertMode(quiz, GameMode.GROUP);
        assertDraft(quiz);

        if (categoryIds == null || categoryIds.size() < 2) {
            throw new ApiException(ErrorCode.QUIZ_TOPICS_REQUIRED);
        }

        quizTopicRepository.deleteAll(
                quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId));
        quizTopicRepository.flush();

        List<QuizTopic> topics = new ArrayList<>();
        for (Long categoryId : categoryIds) {
            Category category = categoryService.requireAvailable(categoryId, GameMode.GROUP);

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
     * Publie le quiz : les tickets partent en vente (GROUP), ou le quiz s'ouvre
     * directement au jeu (SOLO, voir {@link #openSolo}).
     *
     * <p>Trois verrous avant publication, parce qu'apres il sera trop tard :
     * recompenses coherentes, sujet exploitable, vivier de questions suffisant.
     */
    @Transactional
    public Quiz publish(Long actorId, Long quizId, Integer voteDurationMinutes) {
        Quiz quiz = require(quizId);
        assertDraft(quiz);

        if (quiz.isSolo()) {
            return openSolo(actorId, quiz);
        }

        if (rewardService.forQuiz(quizId).isEmpty()) {
            throw new ApiException(ErrorCode.QUIZ_NO_REWARDS);
        }

        if (quiz.getTopicMode() == TopicMode.ADMIN) {
            if (quiz.getSelectedCategory() == null) {
                throw new ApiException(ErrorCode.QUIZ_NO_TOPIC,
                        "Choisissez une categorie ou passez le quiz en mode vote.");
            }
            CategoryService.assertAvailable(quiz.getSelectedCategory(), GameMode.GROUP);
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
                CategoryService.assertAvailable(topic.getCategory(), GameMode.GROUP);
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

        // Envoye apres le commit seulement, comme a l'ouverture d'un quiz solo.
        // Quiz masque : seuls les comptes de test, qui le voient, sont prevenus.
        notificationService.notifyAllPlayers(quiz.isTest() || quiz.isHidden(),
                NotificationMessages.groupQuizOpened(quiz));
        return quiz;
    }

    /**
     * Copie un quiz, quel que soit son etat, dans un nouveau brouillon : memes
     * regles, memes recompenses, memes sujets soumis au vote. Rien de son
     * historique (tickets, votes, parties, vivier fige) n'est repris : le quiz
     * d'origine garde le sien intact.
     */
    @Transactional
    public Quiz duplicate(Long actorId, Long quizId) {
        Quiz source = require(quizId);

        Quiz copy = new Quiz();
        copy.setCreatedBy(actorId);
        String title = source.getTitle() + " (copie)";
        copy.setTitle(title.length() > 160 ? title.substring(0, 160) : title);
        copy.setSlug(uniqueSlug(copy.getTitle()));
        copy.setDescription(source.getDescription());
        copy.setThemeLabel(source.getThemeLabel());
        // La copie d'un quiz de test sert a refaire un test.
        copy.setTest(source.isTest());
        copy.setBanner(source.getBanner());
        copy.setGameMode(source.getGameMode());
        copy.setTopicMode(source.getTopicMode());
        // En mode vote, le sujet retenu est le resultat du vote : la copie revotera.
        copy.setSelectedCategory(source.getTopicMode() == TopicMode.VOTE ? null : source.getSelectedCategory());
        copy.setTicketPriceCredits(source.getTicketPriceCredits());
        copy.setMinPlayers(source.getMinPlayers());
        copy.setMaxTicketsTotal(source.getMaxTicketsTotal());
        copy.setMaxTicketsPerPlayer(source.getMaxTicketsPerPlayer());
        copy.setStartDelayMinutes(source.getStartDelayMinutes());
        copy.setPlayMode(source.getPlayMode());
        copy.setQuestionDurationSeconds(source.getQuestionDurationSeconds());
        copy.setQuestionsPerGame(source.getQuestionsPerGame());
        copy.setPointsPerCorrect(source.getPointsPerCorrect());
        copy.setSoloPrizeHtg(source.getSoloPrizeHtg());
        copy.setCloseOnPerfect(source.isCloseOnPerfect());
        copy.setPerfectCooldownMinutes(source.getPerfectCooldownMinutes());
        copy.setDifficultyMix(source.getDifficultyMix());
        copy.setMinScoreThreshold(source.getMinScoreThreshold());
        copy.setClosingCountdownMinutes(source.getClosingCountdownMinutes());
        Quiz saved = quizRepository.save(copy);

        for (QuizTopic topic : quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quizId)) {
            QuizTopic copied = new QuizTopic();
            copied.setQuiz(saved);
            copied.setCategory(topic.getCategory());
            copied.setLabel(topic.getLabel());
            copied.setMedia(topic.getMedia());
            quizTopicRepository.save(copied);
        }

        List<RewardTierDto> tiers = rewardService.forQuiz(quizId).stream()
                .map(reward -> new RewardTierDto(reward.getRankFrom(), reward.getRankTo(),
                        reward.getAmountHtg(), reward.getLabel()))
                .toList();
        if (!tiers.isEmpty()) {
            rewardService.configure(actorId, saved, tiers);
        }

        auditService.record(actorId, "QUIZ_DUPLICATED", "Quiz", String.valueOf(saved.getId()),
                String.valueOf(quizId), copy.getTitle());
        return saved;
    }

    // --- Solo ---------------------------------------------------------------------

    @Transactional
    public Quiz createSolo(Long actorId, SoloQuizDto request) {
        Quiz quiz = new Quiz();
        quiz.setCreatedBy(actorId);
        quiz.setSlug(uniqueSlug(request.title()));
        applySoloFields(quiz, request);

        Quiz saved = quizRepository.save(quiz);
        auditService.record(actorId, "QUIZ_CREATED", "Quiz", String.valueOf(saved.getId()),
                null, "Solo : " + request.title());
        return saved;
    }

    @Transactional
    public Quiz updateSolo(Long actorId, Long id, SoloQuizDto request) {
        Quiz quiz = require(id);
        assertMode(quiz, GameMode.SOLO);

        // Comme en groupe : une fois ouvert, des joueurs ont paye sur la foi de
        // ces regles. Pour les changer, fermer ce quiz et en ouvrir un autre.
        if (quiz.getStatus() != QuizStatus.DRAFT) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Un quiz solo ouvert ne peut plus etre modifie. Fermez-le et creez-en un nouveau.");
        }

        applySoloFields(quiz, request);
        auditService.record(actorId, "QUIZ_UPDATED", "Quiz", String.valueOf(id), null,
                "Solo : " + request.title());
        return quiz;
    }

    /**
     * Ouvre un quiz solo : le vivier est fige, comme au demarrage d'un quiz de
     * groupe, et les parties peuvent etre lancees aussitot.
     */
    private Quiz openSolo(Long actorId, Quiz quiz) {
        Category category = resolveCategory(quiz);
        CategoryService.assertAvailable(category, GameMode.SOLO);
        assertPoolSufficient(quiz, category.getId());

        int frozen = freezePool(quiz, category.getId());
        quiz.setStartedAt(Instant.now());
        quiz.setStatus(QuizStatus.RUNNING);

        auditService.record(actorId, "QUIZ_PUBLISHED", "Quiz", String.valueOf(quiz.getId()),
                QuizStatus.DRAFT.name(), QuizStatus.RUNNING.name() + " (solo, " + frozen
                        + " questions figees)");
        log.info("Quiz solo {} ouvert avec un vivier de {} questions", quiz.getId(), frozen);

        // Envoye apres le commit seulement : une ouverture qui echoue n'annonce rien.
        // Masque, ou quiz solo coupes globalement : seuls les comptes de test sont prevenus.
        notificationService.notifyAllPlayers(quiz.isTest() || quizAccess.hiddenFromPlayers(quiz),
                NotificationMessages.soloQuizOpened(quiz));
        return quiz;
    }

    /**
     * Ferme un quiz solo : plus aucune partie ne peut y etre lancee. Les parties
     * en cours vont a leur terme, et le corrige devient consultable.
     *
     * <p>Verrou exclusif : les lancements en vol (verrou partage) sont enregistres
     * avant la fermeture, aucun ne passe apres.
     */
    @Transactional
    public Quiz closeSolo(Long actorId, Long quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        assertMode(quiz, GameMode.SOLO);

        if (quiz.getStatus() != QuizStatus.RUNNING) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Seul un quiz solo ouvert peut etre ferme.");
        }

        Instant now = Instant.now();
        quiz.setEndsAt(now);
        quiz.setFinishedAt(now);
        quiz.setStatus(QuizStatus.FINISHED);

        auditService.record(actorId, "QUIZ_CLOSED", "Quiz", String.valueOf(quizId),
                QuizStatus.RUNNING.name(), QuizStatus.FINISHED.name());
        return quiz;
    }

    /**
     * Fermeture automatique d'un quiz solo au premier sans-faute, si l'admin l'a
     * demandee. Appelee par le jeu dans la transaction du sans-faute : si elle
     * echoue, rien n'est valide, ni la fermeture ni le prix.
     *
     * <p>Effet immediat pour les nouvelles parties : un lancement en vol attend le
     * commit (verrou partage contre le verrou de ligne de la mise a jour), puis lit
     * le quiz ferme. Les parties deja lancees vont a leur terme.
     *
     * @return true si cet appel a ferme le quiz
     */
    @Transactional
    public boolean closeSoloOnPerfect(Long quizId, Long sessionId) {
        if (quizRepository.closeSoloOnPerfect(quizId, Instant.now()) == 0) {
            return false; // option absente, deja ferme, ou quiz de groupe
        }
        auditService.record(null, "QUIZ_CLOSED", "Quiz", String.valueOf(quizId),
                QuizStatus.RUNNING.name(), QuizStatus.FINISHED.name()
                        + " (sans-faute, partie " + sessionId + ")");
        log.info("Quiz solo {} ferme automatiquement : sans-faute de la partie {}", quizId, sessionId);
        return true;
    }

    /**
     * Ajoute au vivier d'un quiz solo ouvert les questions publiees de son sujet qui
     * n'y sont pas encore (import, publication apres l'ouverture). Rien n'en est
     * retire : une question archivee depuis reste tirable, comme avant.
     *
     * <p>Reserve au mode solo : en groupe, le vivier fige garantit que tous les
     * joueurs du classement tirent dans le meme ensemble. En solo, chaque partie
     * est independante. Verrou exclusif, comme a la fermeture : les tirages en vol
     * finissent avant l'ajout.
     *
     * @return le nombre de questions ajoutees
     */
    @Transactional
    public int addNewQuestionsToSoloPool(Long actorId, Long quizId) {
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        assertMode(quiz, GameMode.SOLO);

        if (quiz.getStatus() != QuizStatus.RUNNING) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE,
                    "Seul un quiz solo ouvert peut recevoir de nouvelles questions.");
        }

        Set<Long> inPool = new HashSet<>(quizQuestionPoolRepository.findQuestionIds(quizId));
        int added = 0;
        for (Question question : questionRepository
                .findByCategoryIdAndStatus(resolveCategory(quiz).getId(), QuestionStatus.PUBLISHED,
                        Pageable.unpaged())
                .getContent()) {
            if (inPool.add(question.getId())) {
                QuizQuestionPool entry = new QuizQuestionPool();
                entry.setQuiz(quiz);
                entry.setQuestion(question);
                quizQuestionPoolRepository.save(entry);
                added++;
            }
        }

        auditService.record(actorId, "QUIZ_POOL_EXTENDED", "Quiz", String.valueOf(quizId), null,
                added + " question(s) ajoutee(s), vivier de " + inPool.size());
        log.info("Quiz solo {} : {} question(s) ajoutee(s) au vivier ({} au total)",
                quizId, added, inPool.size());
        return added;
    }

    private void applySoloFields(Quiz quiz, SoloQuizDto request) {
        quiz.setGameMode(GameMode.SOLO);
        quiz.setTitle(request.title().trim());
        quiz.setDescription(request.description());
        quiz.setTopicMode(TopicMode.ADMIN);
        quiz.setSelectedCategory(
                categoryService.requireAvailable(request.categoryId(), GameMode.SOLO));
        quiz.setBanner(request.bannerMediaId() == null ? null
                : mediaService.require(request.bannerMediaId()));
        quiz.setTicketPriceCredits(request.entryPriceCredits());
        quiz.setSoloPrizeHtg(request.prizeHtg());
        quiz.setCloseOnPerfect(request.closesOnPerfect());
        quiz.setPerfectCooldownMinutes(request.perfectCooldownMinutes());
        quiz.setDifficultyMix(request.difficultyMix() == null ? null : request.difficultyMix().toMix());
        // Chaque partie engage de l'argent et un sans-faute en rapporte : le joueur
        // ne doit jamais voir une question avant son tour.
        quiz.setPlayMode(PlayMode.SEQUENTIAL);
        quiz.setQuestionDurationSeconds(request.questionDurationSeconds());
        quiz.setQuestionsPerGame(request.questionsPerGame());
        // Sans objet en solo (pas de seuil de joueurs ni de cloture declenchee),
        // mais les colonnes sont obligatoires.
        quiz.setMinPlayers(1);
        quiz.setStartDelayMinutes(0);
        quiz.setMinScoreThreshold(0);
    }

    private static void assertMode(Quiz quiz, GameMode expected) {
        if (quiz.getGameMode() != expected) {
            throw new ApiException(ErrorCode.QUIZ_INVALID_STATE, expected == GameMode.SOLO
                    ? "Action reservee aux quiz solo."
                    : "Action reservee aux quiz de groupe.");
        }
    }

    // --- Groupe ----------------------------------------------------------------

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
    public Page<QuizResponse> viewVisible(GameMode mode, Pageable pageable) {
        return visibleToPlayers(mode, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewVisible(GameMode mode, boolean includeTests, Pageable pageable) {
        return viewVisible(mode, includeTests, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewVisible(GameMode mode, boolean includeTests, Long viewerId, Pageable pageable) {
        return visibleToPlayers(mode, includeTests, viewerId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<QuizResponse> viewByStatus(QuizStatus status, GameMode mode, Pageable pageable) {
        return viewByStatus(status, mode, AdminQuizFilter.NONE, pageable);
    }

    /**
     * Tris de la liste du back-office. « players » compte les joueurs inscrits comme
     * {@link TicketService#registeredPlayers} ; « deadline » suit l'echeance affichee
     * (fin, cloture, demarrage effectif ou prevu, sinon creation).
     */
    private static final SortableFields ADMIN_SORTS = SortableFields
            .of("id", Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
            .field("title", "title")
            .field("category", SortableFields.Key.alwaysAscending("(case when c.id is null then 1 else 0 end)"),
                    SortableFields.Key.of("c.name"))
            .field("ticketPrice", "ticketPriceCredits")
            .field("players", "(select count(distinct t.user.id) from Ticket t where t.quiz = z "
                    + "and t.status <> " + TicketStatus.class.getName() + ".REFUNDED "
                    + "and (z.test = true or t.test = false))")
            .field("deadline", "(coalesce(z.finishedAt, z.endsAt, z.startedAt, z.scheduledStartAt, z.createdAt))")
            .field("createdAt", "createdAt");

    /** Liste du back-office : recherche et filtres cumulables, tri valide par {@link #ADMIN_SORTS}. */
    @Transactional(readOnly = true)
    public Page<QuizResponse> viewByStatus(QuizStatus status, GameMode mode, AdminQuizFilter filter,
            Pageable pageable) {
        return quizRepository.adminList(status, mode, SearchTerms.contains(filter.query()),
                        filter.categoryId(), filter.free(), filter.test(), ADMIN_SORTS.apply(pageable))
                .map(this::toResponse);
    }

    QuizResponse toResponse(Quiz quiz) {
        return QuizResponse.from(
                quiz,
                rewardService.forQuiz(quiz.getId()),
                quizTopicRepository.findByQuizIdOrderByVoteCountDescIdAsc(quiz.getId()),
                ticketService.registeredPlayers(quiz),
                ticketService.soldTickets(quiz),
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
                : categoryService.requireAvailable(request.categoryId(), GameMode.GROUP));
        quiz.setBanner(request.bannerMediaId() == null ? null
                : mediaService.require(request.bannerMediaId()));
        quiz.setTicketPriceCredits(request.ticketPriceCredits());
        quiz.setMinPlayers(request.minPlayers());
        quiz.setMaxTicketsTotal(request.maxTicketsTotal());
        // Gratuit : un seul ticket par joueur. Sans cette limite, des tickets offerts
        // a volonte permettraient de rejouer jusqu'au meilleur score.
        quiz.setMaxTicketsPerPlayer(quiz.isFree() ? Integer.valueOf(1) : request.maxTicketsPerPlayer());
        quiz.setStartDelayMinutes(request.startDelayMinutes());
        quiz.setPlayMode(request.playMode() == null ? PlayMode.SEQUENTIAL : request.playMode());
        quiz.setQuestionDurationSeconds(request.questionDurationSeconds());
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
