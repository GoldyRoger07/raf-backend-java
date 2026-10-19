package com.raikiservice.raf.game.service;

import java.util.Collection;

import com.raikiservice.raf.question.repository.QuestionRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alimente times_served / times_correct, dans sa propre transaction, apres le
 * commit de la partie et hors du thread de la requete
 * ({@code AfterTransactionExecutor}).
 *
 * <p>Pourquoi pas dans la transaction de la partie : inserer le tirage ou les
 * reponses pose un verrou partage sur les questions (cle etrangere). Mettre
 * ensuite a jour ces memes lignes exige un verrou exclusif, et deux parties qui
 * partagent des questions s'interbloquent en tentant chacune cette promotion.
 * Ici, aucun verrou partage n'est detenu, et l'UPDATE verrouille les lignes dans
 * l'ordre de la cle primaire.
 *
 * <p>Ce sont des statistiques : une mise a jour perdue est sans consequence, une
 * partie refusee pour elles ne le serait pas.
 */
@Component
@RequiredArgsConstructor
public class QuestionStatsRecorder {

    private final QuestionRepository questionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Collection<Long> served, Collection<Long> answeredCorrectly) {
        if (!served.isEmpty()) {
            questionRepository.incrementTimesServed(served);
        }
        if (!answeredCorrectly.isEmpty()) {
            questionRepository.incrementTimesCorrect(answeredCorrectly);
        }
    }
}
