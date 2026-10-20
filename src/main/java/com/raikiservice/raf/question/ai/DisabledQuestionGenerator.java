package com.raikiservice.raf.question.ai;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Generation desactivee ({@code app.ai.provider=none}, par defaut) : le back-office l'indique. */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "none", matchIfMissing = true)
public class DisabledQuestionGenerator implements QuestionGenerator {

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return "-";
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public List<GeneratedQuestion> generate(GenerationRequest request) {
        throw new AiGenerationException("Generation par IA desactivee.");
    }
}
