package com.raikiservice.raf.question.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingKeys;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.question.ai.AiGenerationException;
import com.raikiservice.raf.question.ai.GeneratedQuestion;
import com.raikiservice.raf.question.ai.GenerationPrompt;
import com.raikiservice.raf.question.ai.GenerationRequest;
import com.raikiservice.raf.question.ai.QuestionGenerator;
import com.raikiservice.raf.question.domain.AiGenerationRun;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.repository.AiGenerationRunRepository;
import com.raikiservice.raf.question.repository.QuestionRepository;
import com.raikiservice.raf.question.web.dto.ChoiceDto;
import com.raikiservice.raf.question.web.dto.QuestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Generation de questions par IA : un moyen de plus d'alimenter la banque, a
 * cote de la saisie et de l'import CSV.
 *
 * <p>Ce que le modele renvoie n'est jamais cru sur parole : chaque question est
 * verifiee ici (une seule bonne reponse, propositions distinctes, longueurs),
 * puis enregistree EN BROUILLON. Elle ne rejoint le vivier des quiz qu'une fois
 * relue et publiee par un humain.
 *
 * <p>Volontairement NON transactionnel, comme l'import CSV : l'appel au modele
 * dure jusqu'a une ou deux minutes et ne doit tenir aucune connexion, et chaque
 * question est enregistree dans sa propre transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionGenerationService {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");
    private static final long DEFAULT_MAX_PER_REQUEST = 30L;
    private static final long DEFAULT_MAX_PER_DAY = 20L;
    private static final int MAX_TEXT = 2000;
    private static final int MAX_CHOICE = 500;
    private static final int MAX_EXPLANATION = 1000;

    private final QuestionGenerator generator;
    private final CategoryService categoryService;
    private final QuestionService questionService;
    private final QuestionRowImporter rowImporter;
    private final QuestionRepository questionRepository;
    private final AiGenerationRunRepository runRepository;
    private final SettingsService settingsService;
    private final AuditService auditService;

    public record Status(boolean enabled, String provider, String model, long maxQuestionsPerRequest,
            long maxRequestsPerDay, long requestsToday) {
    }

    public record Rejection(String excerpt, String reason) {
    }

    public record Report(String provider, String model, int requested, int created, int duplicates,
            int rejected, List<Rejection> rejections, List<QuestionResponse> questions) {
    }

    public Status status() {
        return new Status(generator.isConfigured(), generator.provider(), generator.model(),
                maxPerRequest(), maxPerDay(), requestsToday());
    }

    public Report generate(Long actorId, Long categoryId, int count, Difficulty difficulty,
            QuestionType type, String instructions) {

        if (!generator.isConfigured()) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }
        long maxPerRequest = maxPerRequest();
        if (count < 1 || count > maxPerRequest) {
            throw new ApiException(ErrorCode.AI_TOO_MANY_QUESTIONS,
                    "Entre 1 et " + maxPerRequest + " questions par demande.");
        }
        if (requestsToday() >= maxPerDay()) {
            throw new ApiException(ErrorCode.AI_DAILY_LIMIT_REACHED,
                    "Maximum " + maxPerDay() + " generations par jour : reessayez demain.");
        }

        Category category = categoryService.require(categoryId);
        List<String> avoid = questionRepository.recentTexts(categoryId,
                PageRequest.of(0, GenerationPrompt.MAX_AVOID));
        GenerationRequest request = new GenerationRequest(category.getName(), category.getDescription(),
                count, difficulty, type, trim(instructions), avoid);

        AiGenerationRun run = new AiGenerationRun();
        run.setActorId(actorId);
        run.setCategoryId(categoryId);
        run.setProvider(generator.provider());
        run.setModel(generator.model());
        run.setRequested(count);

        long start = System.currentTimeMillis();
        List<GeneratedQuestion> generated;
        try {
            generated = generator.generate(request);
        } catch (AiGenerationException e) {
            run.setDurationMs(System.currentTimeMillis() - start);
            run.setError(truncate(e.getMessage(), 500));
            runRepository.save(run);
            log.warn("Generation IA echouee ({} / {}) : {}", generator.provider(), generator.model(),
                    e.getMessage());
            throw new ApiException(ErrorCode.AI_GENERATION_FAILED, e.getMessage());
        }
        run.setDurationMs(System.currentTimeMillis() - start);

        List<Rejection> rejections = new ArrayList<>();
        List<Long> createdIds = new ArrayList<>();
        int duplicates = 0;

        // Le modele peut en renvoyer plus que demande : on s'en tient au nombre demande.
        for (GeneratedQuestion question : generated.stream().limit(count).toList()) {
            try {
                Candidate candidate = check(question, difficulty, type);
                createdIds.add(rowImporter.importGenerated(actorId, categoryId, candidate.text(),
                        candidate.type(), candidate.difficulty(), candidate.explanation(),
                        candidate.choices(), generator.model()));
            } catch (ApiException e) {
                if (e.getErrorCode() == ErrorCode.QUESTION_DUPLICATE) {
                    duplicates++;
                } else {
                    rejections.add(new Rejection(excerpt(question), e.getMessage()));
                }
            } catch (IllegalArgumentException e) {
                rejections.add(new Rejection(excerpt(question), e.getMessage()));
            }
        }
        if (generated.size() < count) {
            rejections.add(new Rejection(null,
                    "Le modele n'a produit que " + generated.size() + " question(s) sur " + count + "."));
        }

        run.setCreatedCount(createdIds.size());
        run.setDuplicates(duplicates);
        run.setRejected(rejections.size());
        runRepository.save(run);

        auditService.record(actorId, "QUESTIONS_GENERATED", "Category", String.valueOf(categoryId),
                null, generator.provider() + " / " + generator.model() + " : " + createdIds.size()
                        + " creee(s), " + duplicates + " en double, " + rejections.size() + " rejetee(s)");

        List<QuestionResponse> questions = createdIds.stream().map(questionService::get).toList();
        return new Report(generator.provider(), generator.model(), count, createdIds.size(), duplicates,
                rejections.size(), rejections, questions);
    }

    /**
     * Verifie et normalise une question generee.
     *
     * <p>Le type se deduit du nombre de propositions, comme pour l'import CSV. Une
     * difficulte imposee par la demande l'emporte sur celle choisie par le modele.
     *
     * @throws IllegalArgumentException avec le motif du rejet, montre a l'administrateur
     */
    private Candidate check(GeneratedQuestion question, Difficulty wantedDifficulty, QuestionType wantedType) {
        String text = trim(question.text());
        if (text == null) {
            throw new IllegalArgumentException("Enonce vide.");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Enonce trop long.");
        }

        List<GeneratedQuestion.Choice> raw = question.choices() == null ? List.of() : question.choices();
        List<ChoiceDto> choices = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GeneratedQuestion.Choice choice : raw) {
            String choiceText = trim(choice == null ? null : choice.text());
            if (choiceText == null) {
                throw new IllegalArgumentException("Proposition vide.");
            }
            if (choiceText.length() > MAX_CHOICE) {
                throw new IllegalArgumentException("Proposition trop longue.");
            }
            if (!seen.add(choiceText.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Deux propositions identiques.");
            }
            choices.add(new ChoiceDto(choiceText, Boolean.TRUE.equals(choice.correct()), null));
        }

        if (choices.size() < 2 || choices.size() > 6) {
            throw new IllegalArgumentException("Entre 2 et 6 propositions attendues (recu : " + choices.size() + ").");
        }
        long correct = choices.stream().filter(ChoiceDto::correct).count();
        if (correct != 1) {
            throw new IllegalArgumentException("Exactement une bonne reponse attendue (recu : " + correct + ").");
        }

        QuestionType type = choices.size() == 2 ? QuestionType.TRUE_FALSE : QuestionType.SINGLE_CHOICE;
        if (wantedType != null && type != wantedType) {
            throw new IllegalArgumentException(wantedType == QuestionType.TRUE_FALSE
                    ? "Vrai / faux demande, QCM recu." : "QCM demande, vrai / faux recu.");
        }
        if (type == QuestionType.SINGLE_CHOICE) {
            // Les modeles placent volontiers la bonne reponse en premier.
            Collections.shuffle(choices);
        }

        return new Candidate(text, type,
                wantedDifficulty != null ? wantedDifficulty : parseDifficulty(question.difficulty()),
                truncate(trim(question.explanation()), MAX_EXPLANATION), choices);
    }

    private Difficulty parseDifficulty(String value) {
        if (value == null) {
            return Difficulty.MEDIUM;
        }
        try {
            return Difficulty.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Difficulty.MEDIUM;
        }
    }

    private long requestsToday() {
        Instant startOfDay = LocalDate.now(HAITI).atStartOfDay(HAITI).toInstant();
        return runRepository.countByCreatedAtGreaterThanEqual(startOfDay);
    }

    private long maxPerRequest() {
        return settingsService.getLong(SettingKeys.AI_MAX_QUESTIONS_PER_REQUEST, DEFAULT_MAX_PER_REQUEST);
    }

    private long maxPerDay() {
        return settingsService.getLong(SettingKeys.AI_MAX_REQUESTS_PER_DAY, DEFAULT_MAX_PER_DAY);
    }

    private static String excerpt(GeneratedQuestion question) {
        String text = trim(question.text());
        return text == null ? null : truncate(text, 80);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private record Candidate(String text, QuestionType type, Difficulty difficulty, String explanation,
            List<ChoiceDto> choices) {
    }
}
