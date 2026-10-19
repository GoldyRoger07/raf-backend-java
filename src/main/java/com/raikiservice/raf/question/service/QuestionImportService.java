package com.raikiservice.raf.question.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.settings.SettingsService;
import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;
import com.raikiservice.raf.question.web.dto.ChoiceDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Import en masse de questions depuis un CSV.
 *
 * <p>Format attendu (en-tete obligatoire, separateur ; ou , detecte automatiquement) :
 *
 * <pre>
 * question;reponse_a;reponse_b;reponse_c;reponse_d;bonne_reponse;difficulte;explication
 * </pre>
 *
 * <p>{@code bonne_reponse} vaut A, B, C ou D. {@code difficulte} vaut FACILE,
 * MOYEN ou DIFFICILE (vide = MOYEN). {@code reponse_c} et {@code reponse_d} peuvent
 * etre vides pour une question vrai/faux.
 *
 * <p>Une ligne invalide n'interrompt pas l'import : elle est rejetee et motivee
 * dans le rapport. Sur un fichier de plusieurs centaines de lignes preparees a la
 * main, tout annuler pour une faute de frappe serait inexploitable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionImportService {

    private static final long DEFAULT_MAX_ROWS = 2000L;

    private final QuestionRowImporter rowImporter;
    private final SettingsService settingsService;
    private final AuditService auditService;

    public record RowError(long line, String reason, String excerpt) {
    }

    public record ImportReport(int imported, int skipped, int failed, List<RowError> errors) {
    }

    /**
     * Volontairement NON transactionnel : chaque ligne est importee dans sa propre
     * transaction par {@link QuestionRowImporter}. Une transaction unique couvrant
     * des milliers de lignes serait de toute facon a proscrire (verrous tenus trop
     * longtemps), et le moindre echec de ligne la condamnerait entierement.
     */
    public ImportReport importCsv(Long actorId, Long categoryId, MultipartFile file,
            boolean publishDirectly) {

        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Aucun fichier fourni.");
        }

        long maxRows = settingsService.getLong("question.import_max_rows", DEFAULT_MAX_ROWS);

        int imported = 0;
        int skipped = 0;
        List<RowError> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            reader.mark(8192);
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Fichier vide.");
            }
            reader.reset();

            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setDelimiter(detectDelimiter(headerLine))
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .build();

            try (CSVParser parser = CSVParser.parse(reader, format)) {
                for (CSVRecord record : parser) {
                    long line = record.getRecordNumber() + 1;

                    if (imported + skipped + errors.size() >= maxRows) {
                        throw new ApiException(ErrorCode.IMPORT_TOO_MANY_ROWS,
                                "Maximum " + maxRows + " lignes par import.");
                    }

                    try {
                        ParsedRow row = parse(record);
                        rowImporter.importRow(actorId, categoryId, row.text(),
                                row.choices().size() == 2 ? QuestionType.TRUE_FALSE
                                        : QuestionType.SINGLE_CHOICE,
                                row.difficulty(), row.explanation(), row.choices(),
                                publishDirectly);
                        imported++;
                    } catch (ApiException e) {
                        if (e.getErrorCode() == ErrorCode.QUESTION_DUPLICATE) {
                            // Un reimport du meme fichier est un usage normal :
                            // on ignore sans le compter comme une erreur.
                            skipped++;
                        } else {
                            errors.add(new RowError(line, e.getMessage(), excerpt(record)));
                        }
                    } catch (RuntimeException e) {
                        errors.add(new RowError(line, "Ligne illisible : " + e.getMessage(),
                                excerpt(record)));
                    }
                }
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.IMPORT_FILE_INVALID, "Lecture du fichier impossible.");
        }

        auditService.record(actorId, "QUESTIONS_IMPORTED", "Category", String.valueOf(categoryId),
                null, imported + " importee(s), " + skipped + " ignoree(s), "
                        + errors.size() + " en erreur");

        return new ImportReport(imported, skipped, errors.size(), errors);
    }

    /** Les tableurs francophones exportent en point-virgule, les anglophones en virgule. */
    private char detectDelimiter(String headerLine) {
        long semicolons = headerLine.chars().filter(c -> c == ';').count();
        long commas = headerLine.chars().filter(c -> c == ',').count();
        return semicolons >= commas ? ';' : ',';
    }

    private ParsedRow parse(CSVRecord record) {
        String text = required(record, "question");

        List<ChoiceDto> choices = new ArrayList<>();
        String correct = required(record, "bonne_reponse").toUpperCase(Locale.ROOT);

        String[] columns = { "reponse_a", "reponse_b", "reponse_c", "reponse_d" };
        String[] letters = { "A", "B", "C", "D" };

        for (int i = 0; i < columns.length; i++) {
            String value = optional(record, columns[i]);
            if (value != null && !value.isBlank()) {
                choices.add(new ChoiceDto(value, letters[i].equals(correct), null));
            }
        }

        if (choices.size() < 2) {
            throw new IllegalArgumentException("au moins deux reponses sont requises");
        }
        if (choices.stream().noneMatch(ChoiceDto::correct)) {
            throw new IllegalArgumentException(
                    "bonne_reponse='" + correct + "' ne correspond a aucune reponse remplie");
        }

        return new ParsedRow(text, choices, parseDifficulty(optional(record, "difficulte")),
                optional(record, "explication"));
    }

    private Difficulty parseDifficulty(String value) {
        if (value == null || value.isBlank()) {
            return Difficulty.MEDIUM;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "FACILE", "EASY" -> Difficulty.EASY;
            case "DIFFICILE", "HARD" -> Difficulty.HARD;
            case "MOYEN", "MEDIUM" -> Difficulty.MEDIUM;
            default -> throw new IllegalArgumentException(
                    "difficulte '" + value + "' inconnue (attendu FACILE, MOYEN ou DIFFICILE)");
        };
    }

    private String required(CSVRecord record, String column) {
        String value = optional(record, column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("colonne '" + column + "' vide ou absente");
        }
        return value;
    }

    private String optional(CSVRecord record, String column) {
        try {
            return record.isSet(column) ? record.get(column) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String excerpt(CSVRecord record) {
        String raw = optional(record, "question");
        if (raw == null) {
            return null;
        }
        return raw.length() <= 60 ? raw : raw.substring(0, 60) + "...";
    }

    private record ParsedRow(String text, List<ChoiceDto> choices, Difficulty difficulty,
            String explanation) {
    }
}
