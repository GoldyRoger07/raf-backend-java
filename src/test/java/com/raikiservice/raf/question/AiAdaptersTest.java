package com.raikiservice.raf.question;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.raikiservice.raf.common.config.AppProperties;
import com.raikiservice.raf.question.ai.AiGenerationException;
import com.raikiservice.raf.question.ai.AnthropicQuestionGenerator;
import com.raikiservice.raf.question.ai.GeneratedQuestion;
import com.raikiservice.raf.question.ai.GenerationRequest;
import com.raikiservice.raf.question.ai.OpenAiCompatibleQuestionGenerator;
import com.raikiservice.raf.question.domain.Difficulty;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les deux adaptateurs face a un faux fournisseur HTTP local : format de la
 * requete envoyee et lecture de la reponse. Aucun appel reel, aucune cle.
 */
class AiAdaptersTest {

    private static final GenerationRequest REQUEST = new GenerationRequest(
            "Histoire d'Haiti", "Dates et personnages", 2, Difficulty.EASY, null, "Revolution",
            List.of("Qui a proclame l'independance ?"));

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedAuth = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedLength = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String auth = exchange.getRequestHeaders().getFirst("x-api-key");
            receivedAuth.set(auth != null ? auth : exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anthropicForcesTheToolAndReadsItsInput() {
        reply = """
                {"content": [{"type": "tool_use", "name": "enregistrer_questions", "input": {"questions": [
                  {"text": "En quelle annee ?", "type": "SINGLE_CHOICE", "difficulty": "EASY",
                   "choices": [{"text": "1804", "correct": true}, {"text": "1803", "correct": false},
                               {"text": "1805", "correct": false}, {"text": "1791", "correct": false}],
                   "explanation": "1er janvier 1804."}]}}],
                 "stop_reason": "tool_use"}
                """;
        AnthropicQuestionGenerator generator = new AnthropicQuestionGenerator(
                properties("anthropic", "", "sk-test"), objectMapper);

        List<GeneratedQuestion> questions = generator.generate(REQUEST);

        assertThat(generator.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(questions).singleElement().satisfies(question -> {
            assertThat(question.text()).isEqualTo("En quelle annee ?");
            assertThat(question.choices()).hasSize(4);
            assertThat(question.choices().getFirst().correct()).isTrue();
        });
        assertThat(receivedPath.get()).isEqualTo("/v1/messages");
        assertThat(receivedAuth.get()).isEqualTo("sk-test");
        assertThat(receivedBody.get())
                .contains("\"tool_choice\"", "enregistrer_questions", "Histoire d'Haiti", "Revolution",
                        "Qui a proclame l'independance ?");
    }

    @Test
    void openAiCompatibleReadsTheJsonContentEvenInsideACodeBlock() {
        reply = """
                {"choices": [{"message": {"role": "assistant", "content":
                  "```json\\n{\\"questions\\": [{\\"text\\": \\"Vrai ?\\", \\"type\\": \\"TRUE_FALSE\\", \\"difficulty\\": \\"EASY\\", \\"choices\\": [{\\"text\\": \\"Vrai\\", \\"correct\\": true}, {\\"text\\": \\"Faux\\", \\"correct\\": false}], \\"explanation\\": \\"Oui.\\"}]}\\n```"}}]}
                """;
        OpenAiCompatibleQuestionGenerator generator = new OpenAiCompatibleQuestionGenerator(
                properties("openai", "mistral-large-latest", "key-test"), objectMapper);

        List<GeneratedQuestion> questions = generator.generate(REQUEST);

        assertThat(questions).singleElement().satisfies(question -> {
            assertThat(question.type()).isEqualTo("TRUE_FALSE");
            assertThat(question.choices()).extracting(GeneratedQuestion.Choice::text).containsExactly("Vrai", "Faux");
        });
        assertThat(receivedPath.get()).isEqualTo("/chat/completions");
        // Corps de longueur annoncee, pas en flux : certains serveurs lisent vide un corps chunked.
        assertThat(receivedLength.get()).isNotNull();
        assertThat(receivedAuth.get()).isEqualTo("Bearer key-test");
        assertThat(receivedBody.get()).contains("\"json_object\"", "mistral-large-latest");
    }

    @Test
    void providerErrorsAreExplained() {
        status = 401;
        reply = "{\"error\": {\"message\": \"invalid x-api-key\"}}";
        AnthropicQuestionGenerator generator = new AnthropicQuestionGenerator(
                properties("anthropic", "", "sk-bad"), objectMapper);

        assertThatThrownBy(() -> generator.generate(REQUEST))
                .isInstanceOf(AiGenerationException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid x-api-key");
    }

    @Test
    void aLocalServerNeedsNoKeyButAModel() {
        assertThat(new OpenAiCompatibleQuestionGenerator(properties("openai", "llama3.1", ""), objectMapper)
                .isConfigured()).isTrue();
        assertThat(new OpenAiCompatibleQuestionGenerator(properties("openai", "", ""), objectMapper)
                .isConfigured()).isFalse();
        assertThat(new AnthropicQuestionGenerator(properties("anthropic", "", ""), objectMapper)
                .isConfigured()).isFalse();
    }

    private AppProperties properties(String provider, String model, String apiKey) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AppProperties.Ai ai = new AppProperties.Ai(provider, model, apiKey, baseUrl, Duration.ofSeconds(5));
        return new AppProperties(null, null, null, null, null, null, null, null, null, null, ai);
    }
}
