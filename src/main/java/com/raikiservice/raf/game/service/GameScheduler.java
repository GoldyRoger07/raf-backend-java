package com.raikiservice.raf.game.service;

import java.util.List;

import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Maintenance du jeu, toutes les 30 secondes :
 * <ol>
 *   <li>clot les parties echues : BATCH sans soumission (EXPIRED), SEQUENTIAL
 *       abandonnees en cours de route (corrigees avec les reponses donnees) ;
 *   <li>fige le classement et verse les gains des quiz en COMPUTING dont toutes
 *       les parties sont closes.
 * </ol>
 *
 * <p>L'ordre compte : l'expiration est commitee avant que le classement ne soit
 * calcule, chacun dans sa transaction. Chaque quiz est traite separement, pour
 * qu'un quiz en echec n'empeche pas les autres d'aboutir.
 *
 * <p>Meme limite que {@code QuizScheduler} : instance unique supposee. Le
 * classement est protege par le verrou du quiz et par l'idempotence des credits,
 * un double traitement est donc sans effet, mais ShedLock sera necessaire avant
 * de scaler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameScheduler {

    private final GameService gameService;
    private final RankingService rankingService;
    private final QuizRepository quizRepository;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 15_000L)
    public void maintain() {
        try {
            int expired = gameService.expireOverdue();
            if (expired > 0) {
                log.info("{} partie(s) echue(s) sans soumission", expired);
            }
        } catch (RuntimeException e) {
            log.error("Expiration des parties echues impossible", e);
        }

        List<Long> abandoned = List.of();
        try {
            abandoned = gameService.findOverdueSequential();
        } catch (RuntimeException e) {
            log.error("Recherche des parties abandonnees impossible", e);
        }
        for (Long sessionId : abandoned) {
            try {
                if (gameService.completeOverdue(sessionId)) {
                    log.info("Partie {} abandonnee, close avec les reponses donnees", sessionId);
                }
            } catch (RuntimeException e) {
                log.error("Cloture de la partie abandonnee {} impossible", sessionId, e);
            }
        }

        List<Long> computing = quizRepository.findIdsByStatus(QuizStatus.COMPUTING);
        for (Long quizId : computing) {
            try {
                rankingService.finalizeQuiz(null, quizId);
            } catch (RuntimeException e) {
                log.error("Classement du quiz {} impossible", quizId, e);
            }
        }
    }
}
