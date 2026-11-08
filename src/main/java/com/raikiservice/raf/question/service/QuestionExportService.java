package com.raikiservice.raf.question.service;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.question.domain.Category;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.Question;
import com.raikiservice.raf.question.domain.QuestionChoice;
import com.raikiservice.raf.question.domain.QuestionStatus;
import com.raikiservice.raf.question.repository.CategoryRepository;
import com.raikiservice.raf.question.repository.QuestionRepository;

import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Export CSV des questions d'une categorie, au format de l'import
 * ({@link QuestionImportService}) : le fichier se reimporte tel quel, dans la
 * meme categorie ou une autre.
 *
 * <pre>
 * question;reponse_a;reponse_b;reponse_c;reponse_d;bonne_reponse;difficulte;explication
 * </pre>
 *
 * <p>Les colonnes {@code reponse_e} et {@code reponse_f} ne sont ajoutees que si une
 * question exportee compte plus de 4 propositions. Le fichier commence par un BOM
 * UTF-8, pour qu'Excel l'ouvre avec ses accents. Le statut, les images et les
 * statistiques ne font pas partie du format.
 *
 * <p>Le fichier contient les bonnes reponses : chaque export est trace au journal
 * d'audit.
 */
@Service
@RequiredArgsConstructor
public class QuestionExportService {

    static final String[] CHOICE_COLUMNS =
            { "reponse_a", "reponse_b", "reponse_c", "reponse_d", "reponse_e", "reponse_f" };
    static final String[] CHOICE_LETTERS = { "A", "B", "C", "D", "E", "F" };

    /** Colonnes de reponse toujours presentes, comme dans le modele d'import. */
    private static final int BASE_CHOICES = 4;

    private final CategoryRepository categoryRepository;
    private final QuestionRepository questionRepository;
    private final AuditService auditService;

    public record ExportFile(String filename, byte[] content, int count) {
    }

    /** @param status null = tous les statuts */
    @Transactional(readOnly = true)
    public ExportFile exportCsv(Long actorId, Long categoryId, QuestionStatus status) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> ApiException.notFound("Categorie"));

        List<Question> questions = status == null
                ? questionRepository.findWithChoicesByCategoryIdOrderByIdAsc(categoryId)
                : questionRepository.findWithChoicesByCategoryIdAndStatusOrderByIdAsc(categoryId, status);

        int choiceColumns = Math.max(BASE_CHOICES, questions.stream()
                .mapToInt(question -> question.getChoices().size())
                .max().orElse(0));
        choiceColumns = Math.min(choiceColumns, CHOICE_COLUMNS.length);

        List<String> header = new ArrayList<>();
        header.add("question");
        header.addAll(List.of(CHOICE_COLUMNS).subList(0, choiceColumns));
        header.add("bonne_reponse");
        header.add("difficulte");
        header.add("explication");

        StringWriter out = new StringWriter();
        out.write('﻿');
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setHeader(header.toArray(String[]::new))
                .build();

        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            for (Question question : questions) {
                printer.printRecord(row(question, choiceColumns));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        auditService.record(actorId, "QUESTIONS_EXPORTED", "Category", String.valueOf(categoryId),
                null, questions.size() + " question(s)"
                        + (status == null ? "" : ", statut " + status));

        String filename = "questions-" + category.getSlug()
                + (status == null ? "" : "-" + status.name().toLowerCase()) + ".csv";
        return new ExportFile(filename, out.toString().getBytes(StandardCharsets.UTF_8),
                questions.size());
    }

    private static List<String> row(Question question, int choiceColumns) {
        List<QuestionChoice> choices = question.getChoices();
        List<String> row = new ArrayList<>();
        row.add(oneLine(question.getText()));

        String correct = "";
        for (int i = 0; i < choiceColumns; i++) {
            if (i < choices.size()) {
                QuestionChoice choice = choices.get(i);
                row.add(oneLine(choice.getText()));
                if (choice.isCorrect()) {
                    correct = CHOICE_LETTERS[i];
                }
            } else {
                row.add("");
            }
        }
        row.add(correct);
        row.add(difficulty(question.getDifficulty()));
        row.add(question.getExplanation() == null ? "" : oneLine(question.getExplanation()));
        return row;
    }

    private static String difficulty(Difficulty difficulty) {
        if (difficulty == null) {
            return "MOYEN";
        }
        return switch (difficulty) {
            case EASY -> "FACILE";
            case MEDIUM -> "MOYEN";
            case HARD -> "DIFFICILE";
        };
    }

    /**
     * L'import rogne les cellules ; un retour a la ligne, entre guillemets, resterait
     * lisible, mais un tableur l'affiche mal. Les enonces tiennent sur une ligne.
     */
    private static String oneLine(String value) {
        return value.replaceAll("\\s*\\R\\s*", " ").strip();
    }
}
