package com.raikiservice.raf.quiz.service;

import java.time.Instant;
import java.util.List;

import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fait avancer les quiz dont l'echeance est atteinte.
 *
 * <p>Deux transitions sont pilotees par le temps :
 * <ul>
 *   <li>SCHEDULED -> RUNNING quand {@code scheduled_start_at} est passe ;
 *   <li>CLOSING -> COMPUTING quand {@code ends_at} est passe.
 * </ul>
 *
 * <p>Chaque quiz est traite dans sa propre transaction : un quiz en echec ne doit
 * pas empecher les autres d'avancer.
 *
 * <p><strong>Limite connue :</strong> ce planificateur suppose une instance
 * unique. Sur plusieurs instances, chacune traiterait les memes quiz en parallele.
 * Les transitions sont protegees par le verrou pessimiste du quiz et re-verifient
 * l'etat avant d'agir, donc un double traitement est sans effet ; il faudra
 * neanmoins ShedLock avant de scaler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizScheduler {

    private final QuizRepository quizRepository;
    private final QuizService quizService;

    @Scheduled(fixedDelay = 30_000L)
    public void advanceQuizzes() {
        startDueQuizzes();
        closeDueQuizzes();
    }

    @Transactional(readOnly = true)
    void startDueQuizzes() {
        List<Quiz> due = quizRepository.findDueToStart(QuizStatus.SCHEDULED, Instant.now());

        for (Quiz quiz : due) {
            try {
                quizService.start(null, quiz.getId());
            } catch (RuntimeException e) {
                log.error("Demarrage automatique du quiz {} impossible", quiz.getId(), e);
            }
        }
    }

    @Transactional(readOnly = true)
    void closeDueQuizzes() {
        List<Quiz> due = quizRepository.findDueToClose(QuizStatus.CLOSING, Instant.now());

        for (Quiz quiz : due) {
            try {
                quizService.moveToComputing(quiz.getId());
            } catch (RuntimeException e) {
                log.error("Cloture automatique du quiz {} impossible", quiz.getId(), e);
            }
        }
    }
}
