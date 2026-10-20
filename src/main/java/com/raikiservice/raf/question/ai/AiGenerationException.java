package com.raikiservice.raf.question.ai;

/** Echec d'appel au modele, ou reponse inexploitable. Le message est montre a l'administrateur. */
public class AiGenerationException extends RuntimeException {

    public AiGenerationException(String message) {
        super(message);
    }

    public AiGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
