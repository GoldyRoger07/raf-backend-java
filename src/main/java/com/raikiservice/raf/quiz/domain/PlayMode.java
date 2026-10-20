package com.raikiservice.raf.quiz.domain;

/**
 * Deroulement d'une partie. Dans les deux modes, chaque question a son propre
 * chrono et le corrige ne quitte jamais le serveur avant la cloture du quiz.
 */
public enum PlayMode {
    /**
     * Tout le tirage est envoye au demarrage, la partie se joue hors-ligne et
     * les reponses sont soumises d'un bloc. Le chrono par question est tenu par
     * le client : le serveur ne controle que la coherence des temps declares.
     */
    BATCH,
    /**
     * Une question a la fois : la suivante n'est envoyee qu'apres la reponse a
     * la precedente ou l'expiration de son chrono. Le serveur horodate chaque
     * question servie et refuse une reponse arrivee hors delai.
     */
    SEQUENTIAL
}
