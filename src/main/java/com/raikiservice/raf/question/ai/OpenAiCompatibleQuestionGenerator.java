package com.raikiservice.raf.question.ai;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.raikiservice.raf.common.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Toute API au format OpenAI ({@code app.ai.provider=openai}) : OpenAI, Mistral,
 * DeepSeek, Groq, OpenRouter, Ollama en local... {@code app.ai.base-url} choisit
 * le fournisseur.
 *
 * <p>Le mode {@code json_object} est le plus largement pris en charge : il garantit
 * un JSON valide, le format attendu etant rappele dans la consigne.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
public class OpenAiCompatibleQuestionGenerator implements QuestionGenerator {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String FENCE = "```";

    private final AppProperties.Ai config;
    private final String endpoint;
    private final AiHttp http;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleQuestionGenerator(AppProperties properties, ObjectMapper objectMapper) {
        this.config = properties.ai();
        this.endpoint = AiHttp.withoutTrailingSlash(
                isBlank(config.baseUrl()) ? DEFAULT_BASE_URL : config.baseUrl()) + "/chat/completions";
        this.http = new AiHttp(config.timeout(), objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return isBlank(config.model()) ? "(modele non configure)" : config.model();
    }

    /** Sans cle, seul un serveur a adresse explicite (ex. Ollama en local) est utilisable. */
    @Override
    public boolean isConfigured() {
        return !isBlank(config.model()) && (config.hasApiKey() || !isBlank(config.baseUrl()));
    }

    @Override
    public List<GeneratedQuestion> generate(GenerationRequest request) {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "messages", List.of(
                        Map.of("role", "system", "content", GenerationPrompt.SYSTEM),
                        Map.of("role", "user", "content",
                                GenerationPrompt.user(request) + "\n" + GenerationPrompt.JSON_FORMAT)),
                "response_format", Map.of("type", "json_object"));

        String raw = http.post(endpoint, headers -> {
            if (config.hasApiKey()) {
                headers.setBearerAuth(config.apiKey());
            }
        }, body);

        try {
            CompletionResponse response = objectMapper.readValue(raw, CompletionResponse.class);
            String content = response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null
                    ? null : response.choices().getFirst().message().content();
            if (content == null || content.isBlank()) {
                throw new AiGenerationException("Le modele n'a renvoye aucune question.");
            }
            GeneratedQuestion.Batch batch = objectMapper.readValue(stripFences(content),
                    GeneratedQuestion.Batch.class);
            return batch.questions() == null ? List.of() : batch.questions();
        } catch (JacksonException e) {
            throw new AiGenerationException("Reponse du modele illisible : " + AiHttp.excerpt(raw), e);
        }
    }

    /** Certains modeles entourent le JSON d'un bloc de code malgre la consigne. */
    static String stripFences(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith(FENCE)) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf(FENCE);
            if (start > 0 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CompletionResponse(List<Choice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content) {
    }
}
