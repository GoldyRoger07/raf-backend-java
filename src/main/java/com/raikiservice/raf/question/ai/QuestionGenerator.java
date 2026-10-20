package com.raikiservice.raf.question.ai;

import java.util.List;

/**
 * Fournisseur de questions generees par un modele d'IA.
 *
 * <p>Le reste du backend ne connait que cette interface : la consigne
 * ({@link GenerationPrompt}), la verification des questions et leur
 * enregistrement en brouillon sont communs a tous les modeles. Seul
 * l'adaptateur, choisi par {@code app.ai.provider}, change.
 *
 * <p>Un adaptateur ne valide rien : il renvoie ce que le modele a produit, tel
 * quel. La verification est faite une fois pour toutes par
 * {@link com.raikiservice.raf.question.service.QuestionGenerationService}.
 */
public interface QuestionGenerator {

    /** Nom du fournisseur, affiche au back-office et trace avec chaque demande. */
    String provider();

    /** Modele utilise ; recopie sur chaque question generee. */
    String model();

    /** Faux tant que la configuration (cle, modele) ne permet aucun appel. */
    boolean isConfigured();

    /**
     * Appelle le modele.
     *
     * @throws AiGenerationException si l'appel echoue ou si la reponse est illisible
     */
    List<GeneratedQuestion> generate(GenerationRequest request);
}
