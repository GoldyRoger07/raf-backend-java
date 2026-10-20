package com.raikiservice.raf.question.ai;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.raikiservice.raf.common.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API Claude d'Anthropic ({@code app.ai.provider=anthropic}).
 *
 * <p>Le format de reponse est impose par un outil a appel force : le modele ne
 * peut repondre qu'en remplissant le schema {@link GenerationPrompt#SCHEMA}.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "anthropic")
public class AnthropicQuestionGenerator implements QuestionGenerator {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final String API_VERSION = "2023-06-01";
    private static final String TOOL = "enregistrer_questions";

    private final AppProperties.Ai config;
    private final String model;
    private final String endpoint;
    private final AiHttp http;
    private final ObjectMapper objectMapper;

    public AnthropicQuestionGenerator(AppProperties properties, ObjectMapper objectMapper) {
        this.config = properties.ai();
        this.model = isBlank(config.model()) ? DEFAULT_MODEL : config.model();
        this.endpoint = AiHttp.withoutTrailingSlash(
                isBlank(config.baseUrl()) ? DEFAULT_BASE_URL : config.baseUrl()) + "/v1/messages";
        this.http = new AiHttp(config.timeout(), objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return config.hasApiKey();
    }

    @Override
    public List<GeneratedQuestion> generate(GenerationRequest request) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", Math.min(16_000, 1_000 + request.count() * 400),
                "system", GenerationPrompt.SYSTEM,
                "messages", List.of(Map.of("role", "user", "content", GenerationPrompt.user(request))),
                "tools", List.of(Map.of(
                        "name", TOOL,
                        "description", "Enregistre les questions redigees dans la banque, en brouillon.",
                        "input_schema", GenerationPrompt.SCHEMA)),
                "tool_choice", Map.of("type", "tool", "name", TOOL));

        String raw = http.post(endpoint, headers -> {
            headers.set("x-api-key", config.apiKey());
            headers.set("anthropic-version", API_VERSION);
        }, body);

        try {
            MessageResponse response = objectMapper.readValue(raw, MessageResponse.class);
            JsonNode input = response.content() == null ? null : response.content().stream()
                    .filter(block -> "tool_use".equals(block.type()))
                    .map(Block::input)
                    .findFirst()
                    .orElse(null);
            if (input == null) {
                throw new AiGenerationException("Le modele n'a renvoye aucune question.");
            }
            GeneratedQuestion.Batch batch = objectMapper.treeToValue(input, GeneratedQuestion.Batch.class);
            List<GeneratedQuestion> questions = batch.questions() == null ? List.of() : batch.questions();
            if (questions.isEmpty() && "max_tokens".equals(response.stopReason())) {
                throw new AiGenerationException("Reponse coupee avant la fin : demandez moins de questions.");
            }
            return questions;
        } catch (JacksonException e) {
            throw new AiGenerationException("Reponse du modele illisible : " + AiHttp.excerpt(raw), e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageResponse(List<Block> content, @JsonProperty("stop_reason") String stopReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Block(String type, JsonNode input) {
    }
}
