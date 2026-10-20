package com.raikiservice.raf.question.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.common.util.Hashes;
import com.raikiservice.raf.media.service.MediaService;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionSource;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.question.web.dto.QuestionResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionService {

    private static final long DEFAULT_POOL_RATIO_MIN = 10L;

    private final QuestionRepository questionRepository;
    private final CategoryService categoryService;
    private final MediaService mediaService;
    private final SettingsService settingsService;
    private final AuditService auditService;

    /**
     * Acces a l'entite, reserve aux appels internes deja dans une transaction.
     *
     * <p>Ne jamais renvoyer cette entite a un controleur : avec
     * {@code open-in-view=false}, la conversion en DTO se ferait apres la fermeture
     * de la session et toute relation paresseuse leverait une
     * LazyInitializationException. Les methodes publiques renvoient donc des DTO,
     * construits pendant que la session est encore ouverte.
     */
    @Transactional(readOnly = true)
    public Question require(Long id) {
        return questionRepository.findWithChoicesById(id)
                .orElseThrow(() -> ApiException.notFound("Question"));
    }

    @Transactional(readOnly = true)
    public QuestionResponse get(Long id) {
        return QuestionResponse.from(require(id));
    }

    @Transactional(readOnly = true)
    public Page<QuestionResponse> list(QuestionStatus status, Long categoryId, QuestionSource source,
            Pageable pageable) {
        return questionRepository.filter(status, categoryId, source, pageable).map(QuestionResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<QuestionResponse> search(String term, Pageable pageable) {
        return questionRepository.search(term, pageable).map(QuestionResponse::from);
    }

    @Transactional
    public QuestionResponse create(Long actorId, Long categoryId, String text, QuestionType type,
            Difficulty difficulty, String explanation, Long mediaId, List<ChoiceDto> choices) {
        return QuestionResponse.from(createEntity(actorId, categoryId, text, type, difficulty,
                explanation, mediaId, choices));
    }

    @Transactional
    public Question createEntity(Long actorId, Long categoryId, String text, QuestionType type,
            Difficulty difficulty, String explanation, Long mediaId, List<ChoiceDto> choices) {

        Category category = categoryService.require(categoryId);
        String hash = fingerprint(text);

        if (questionRepository.existsByCategoryIdAndTextHash(categoryId, hash)) {
            throw new ApiException(ErrorCode.QUESTION_DUPLICATE);
        }

        Question question = new Question();
        question.setCategory(category);
        question.setCreatedBy(actorId);
        applyFields(question, text, hash, type, difficulty, explanation, mediaId);
        applyChoices(question, choices);

        Question saved = questionRepository.save(question);
        auditService.record(actorId, "QUESTION_CREATED", "Question",
                String.valueOf(saved.getId()), null, text);
        return saved;
    }

    @Transactional
    public QuestionResponse update(Long actorId, Long id, Long categoryId, String text,
            QuestionType type, Difficulty difficulty, String explanation, Long mediaId,
            List<ChoiceDto> choices) {
        return QuestionResponse.from(updateEntity(actorId, id, categoryId, text, type, difficulty,
                explanation, mediaId, choices));
    }

    @Transactional
    public Question updateEntity(Long actorId, Long id, Long categoryId, String text, QuestionType type,
            Difficulty difficulty, String explanation, Long mediaId, List<ChoiceDto> choices) {

        Question question = require(id);
        String previous = question.getText();
        String hash = fingerprint(text);

        Long targetCategoryId = categoryId == null ? question.getCategory().getId() : categoryId;
        questionRepository.findByCategoryIdAndTextHash(targetCategoryId, hash)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.QUESTION_DUPLICATE);
                });

        if (categoryId != null) {
            question.setCategory(categoryService.require(categoryId));
        }
        applyFields(question, text, hash, type, difficulty, explanation, mediaId);

        if (choices != null && !choices.isEmpty()) {
            question.getChoices().clear();
            applyChoices(question, choices);
        }

        auditService.record(actorId, "QUESTION_UPDATED", "Question", String.valueOf(id),
                previous, text);
        return question;
    }

    @Transactional
    public QuestionResponse changeStatus(Long actorId, Long id, QuestionStatus status) {
        Question question = require(id);
        QuestionStatus previous = question.getStatus();

        if (status == QuestionStatus.PUBLISHED) {
            assertExactlyOneCorrect(question.getChoices());
        }

        question.setStatus(status);
        auditService.record(actorId, "QUESTION_STATUS_CHANGED", "Question", String.valueOf(id),
                previous.name(), status.name());
        return QuestionResponse.from(question);
    }

    /**
     * Suppression reservee aux questions jamais servies.
     *
     * <p>Une question deja jouee est referencee par les parties : l'effacer rendrait
     * incontestable un classement deja publie. On l'archive.
     */
    @Transactional
    public void delete(Long actorId, Long id) {
        Question question = require(id);

        if (question.getTimesServed() > 0) {
            throw new ApiException(ErrorCode.QUESTION_IN_USE);
        }

        questionRepository.delete(question);
        auditService.record(actorId, "QUESTION_DELETED", "Question", String.valueOf(id),
                question.getText(), null);
    }

    /**
     * Etat du vivier d'une categorie.
     *
     * <p>Consomme au lot 5 pour empecher de publier un quiz dont le pool est trop
     * maigre : avec un vivier a peine plus grand que le nombre de questions servies,
     * deux joueurs recoivent presque le meme tirage et les enonces fuitent vite.
     */
    @Transactional(readOnly = true)
    public PoolStatus poolStatus(Long categoryId, int questionsPerGame) {
        categoryService.require(categoryId);

        long ratio = settingsService.getLong(
                SettingKeys.GAME_POOL_RATIO_MIN, DEFAULT_POOL_RATIO_MIN);
        long available = questionRepository.countByCategoryIdAndStatus(
                categoryId, QuestionStatus.PUBLISHED);
        long required = (long) questionsPerGame * ratio;

        return new PoolStatus(
                categoryId,
                available,
                required,
                available >= required,
                questionRepository.countByCategoryIdAndStatusAndDifficulty(
                        categoryId, QuestionStatus.PUBLISHED, Difficulty.EASY),
                questionRepository.countByCategoryIdAndStatusAndDifficulty(
                        categoryId, QuestionStatus.PUBLISHED, Difficulty.MEDIUM),
                questionRepository.countByCategoryIdAndStatusAndDifficulty(
                        categoryId, QuestionStatus.PUBLISHED, Difficulty.HARD));
    }

    public record PoolStatus(Long categoryId, long available, long required, boolean sufficient,
            long easy, long medium, long hard) {
    }

    private void applyFields(Question question, String text, String hash, QuestionType type,
            Difficulty difficulty, String explanation, Long mediaId) {
        question.setText(text.trim());
        question.setTextHash(hash);
        question.setType(type == null ? QuestionType.SINGLE_CHOICE : type);
        question.setDifficulty(difficulty == null ? Difficulty.MEDIUM : difficulty);
        question.setExplanation(explanation);
        question.setMedia(mediaId == null ? null : mediaService.require(mediaId));
    }

    private void applyChoices(Question question, List<ChoiceDto> choices) {
        assertExactlyOneCorrect(choices);

        int position = 1;
        for (ChoiceDto dto : choices) {
            QuestionChoice choice = new QuestionChoice();
            choice.setText(dto.text().trim());
            choice.setCorrect(dto.correct());
            choice.setPosition(position++);
            choice.setMedia(dto.mediaId() == null ? null : mediaService.require(dto.mediaId()));
            question.addChoice(choice);
        }
    }

    private void assertExactlyOneCorrect(List<?> choices) {
        if (choices == null || choices.size() < 2) {
            throw new ApiException(ErrorCode.QUESTION_INVALID_CHOICES,
                    "Une question doit proposer au moins deux reponses.");
        }

        long correct = choices.stream()
                .mapToLong(choice -> isCorrect(choice) ? 1 : 0)
                .sum();

        if (correct != 1) {
            throw new ApiException(ErrorCode.QUESTION_INVALID_CHOICES,
                    "Exactement une reponse doit etre marquee correcte (trouve : " + correct + ").");
        }
    }

    private boolean isCorrect(Object choice) {
        if (choice instanceof ChoiceDto dto) {
            return dto.correct();
        }
        return choice instanceof QuestionChoice entity && entity.isCorrect();
    }

    /**
     * Empreinte de l'enonce, insensible a la casse, aux accents et a la ponctuation.
     *
     * <p>C'est ce qui fait qu'un second import du meme fichier ne cree pas de
     * doublons, meme si l'enonce a ete legerement reformate entre-temps.
     */
    static String fingerprint(String text) {
        String normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return Hashes.sha256Hex(normalized);
    }
}
