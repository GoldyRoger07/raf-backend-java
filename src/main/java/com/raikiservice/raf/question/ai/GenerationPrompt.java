package com.raikiservice.raf.question.ai;

import java.util.List;
import java.util.Map;

import com.raikiservice.raf.question.domain.Difficulty;
import com.raikiservice.raf.question.domain.QuestionType;

/**
 * Consigne envoyee au modele, identique pour tous les fournisseurs : changer de
 * modele ne doit pas changer ce qu'on lui demande.
 */
public final class GenerationPrompt {

    /** Au-dela, la liste des enonces a eviter coute plus qu'elle ne rapporte. */
    public static final int MAX_AVOID = 150;

    public static final String SYSTEM = """
            Tu es redacteur de questions pour RAF, une application de quiz haitienne ou les \
            joueurs paient pour jouer et gagnent de l'argent. Une question fausse ou ambigue \
            fait perdre de l'argent a un joueur : l'exactitude passe avant tout.

            Regles :
            - Ecris en francais correct, avec les accents, en phrases courtes et claires, \
            comprehensibles par un public haitien.
            - Chaque question a UNE seule bonne reponse, indiscutable et verifiable.
            - Ne pose une question que si tu es certain de la reponse. En cas de doute sur un \
            fait, choisis une autre question.
            - Evite les faits qui changent avec le temps (\"actuel\", \"record en cours\", \
            \"dernier vainqueur\") sauf si la question precise une date.
            - Les mauvaises reponses sont plausibles, de meme nature et de longueur proche de \
            la bonne. Jamais \"toutes les reponses\" ni \"aucune de ces reponses\".
            - La bonne reponse ne doit pas se deviner a la formulation de l'enonce.
            - Enonce de 300 caracteres au plus, reponse de 120 caracteres au plus.
            - QCM (SINGLE_CHOICE) : exactement 4 propositions. Vrai / faux (TRUE_FALSE) : \
            exactement 2 propositions, \"Vrai\" puis \"Faux\".
            - L'explication (1 a 2 phrases) justifie la bonne reponse : elle sert au relecteur \
            a verifier le fait.
            - Difficulte : EASY = connu de presque tout le monde ; MEDIUM = demande une bonne \
            culture du sujet ; HARD = connu des passionnes.
            - Toutes les questions sont differentes entre elles et differentes des questions \
            deja dans la banque.
            """;

    /** Format de reponse, decrit en JSON Schema (outil Anthropic, ou rappel dans la consigne). */
    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("questions", Map.of(
                    "type", "array",
                    "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "text", Map.of("type", "string"),
                                    "type", Map.of("type", "string",
                                            "enum", List.of("SINGLE_CHOICE", "TRUE_FALSE")),
                                    "difficulty", Map.of("type", "string",
                                            "enum", List.of("EASY", "MEDIUM", "HARD")),
                                    "choices", Map.of(
                                            "type", "array",
                                            "items", Map.of(
                                                    "type", "object",
                                                    "properties", Map.of(
                                                            "text", Map.of("type", "string"),
                                                            "correct", Map.of("type", "boolean")),
                                                    "required", List.of("text", "correct"))),
                                    "explanation", Map.of("type", "string")),
                            "required", List.of("text", "type", "difficulty", "choices",
                                    "explanation")))),
            "required", List.of("questions"));

    /** Rappel du format pour les modeles qui n'imposent pas de schema. */
    public static final String JSON_FORMAT = """
            Reponds uniquement par un objet JSON, sans texte autour, de la forme :
            {"questions": [{"text": "...", "type": "SINGLE_CHOICE", "difficulty": "MEDIUM",
              "choices": [{"text": "...", "correct": true}, {"text": "...", "correct": false}, ...],
              "explanation": "..."}]}
            """;

    public static String user(GenerationRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Sujet : ").append(request.categoryName()).append('\n');
        if (request.categoryDescription() != null && !request.categoryDescription().isBlank()) {
            prompt.append("Description du sujet : ").append(request.categoryDescription()).append('\n');
        }
        prompt.append("Nombre de questions : ").append(request.count()).append('\n');
        prompt.append("Difficulte : ").append(difficulty(request.difficulty())).append('\n');
        prompt.append("Type : ").append(type(request.type())).append('\n');

        if (request.instructions() != null && !request.instructions().isBlank()) {
            prompt.append("\nConsignes du redacteur en chef : ")
                    .append(request.instructions().trim()).append('\n');
        }

        List<String> avoid = request.avoid() == null ? List.of() : request.avoid();
        if (!avoid.isEmpty()) {
            prompt.append("\nQuestions deja dans la banque (ne pas les reproduire, ni les reformuler) :\n");
            avoid.stream().limit(MAX_AVOID).forEach(text -> prompt.append("- ").append(text).append('\n'));
        }
        return prompt.toString();
    }

    private static String difficulty(Difficulty difficulty) {
        return difficulty == null
                ? "melangee, environ un tiers EASY, un tiers MEDIUM, un tiers HARD"
                : "toutes " + difficulty.name();
    }

    private static String type(QuestionType type) {
        if (type == null) {
            return "surtout des QCM (SINGLE_CHOICE), avec environ une question sur cinq en vrai / faux (TRUE_FALSE)";
        }
        return type == QuestionType.TRUE_FALSE ? "uniquement des vrai / faux (TRUE_FALSE)"
                : "uniquement des QCM (SINGLE_CHOICE)";
    }

    private GenerationPrompt() {
    }
}
