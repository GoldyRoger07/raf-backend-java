package com.raikiservice.raf.quiz.service;

import java.util.Set;
import java.util.HashSet;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.settings.PlayerAppService;
import com.raikiservice.raf.quiz.domain.Quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Qui voit les quiz de test : le staff (tout role autre que joueur) et les comptes
 * marques de test. Pour les autres, un quiz de test n'existe pas (404) : aucun vrai
 * joueur ne depense d'argent sur un quiz exclu de la comptabilite.
 *
 * <p>Un quiz masque reste visible du staff, des comptes de test, et des joueurs qui y
 * ont deja un ticket ou une partie ; pour les autres, il n'existe pas non plus.
 */
@Component
@RequiredArgsConstructor
public class QuizAccess {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final GameSessionRepository gameSessionRepository;
    private final PlayerAppService playerAppService;

    @Transactional(readOnly = true)
    public boolean seesTestQuizzes(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> user.isTest()
                        || user.getRoles().stream().anyMatch(role -> role.getName().isStaff()))
                .orElse(false);
    }

    /**
     * Masque aux joueurs : par son propre reglage, ou parce que c'est un quiz solo et
     * que l'interrupteur global des quiz solo est coupe (PlayerAppService).
     */
    public boolean hiddenFromPlayers(Quiz quiz) {
        return quiz.isHidden() || (quiz.isSolo() && !playerAppService.soloQuizzesVisible());
    }

    public void assertVisible(Quiz quiz, Long userId) {
        boolean hidden = hiddenFromPlayers(quiz);
        if ((quiz.isTest() || hidden) && !seesTestQuizzes(userId)
                && !(hidden && !quiz.isTest() && isParticipant(quiz, userId))) {
            throw ApiException.notFound("Quiz");
        }
        if (quiz.getPerfectCooldownMinutes() != null && !cooldownHiddenQuizIds(userId, quiz.getId()).isEmpty()) {
            throw ApiException.notFound("Quiz");
        }
    }

    /** Membre du staff (tout role autre que joueur) : voit toujours tout. */
    @Transactional(readOnly = true)
    public boolean isStaff(Long userId) {
        return userId != null && userRepository.findById(userId)
                .map(user -> user.getRoles().stream().anyMatch(role -> role.getName().isStaff()))
                .orElse(false);
    }

    /**
     * Quiz solo caches a ce joueur parce qu'il y a fait un sans-faute il y a moins que le
     * delai du quiz (compte depuis son dernier sans-faute). Le staff n'est jamais concerne ;
     * les comptes de test le sont, pour pouvoir verifier la regle.
     *
     * @param quizId un quiz precis, ou null pour tous
     */
    @Transactional(readOnly = true)
    public Set<Long> cooldownHiddenQuizIds(Long userId, Long quizId) {
        if (userId == null || isStaff(userId)) {
            return Set.of();
        }
        Instant now = Instant.now();
        Set<Long> hidden = new HashSet<>();
        for (GameSessionRepository.PerfectCooldown c : gameSessionRepository.perfectCooldowns(userId, quizId)) {
            if (c.getLastPerfectAt() != null
                    && now.isBefore(c.getLastPerfectAt().plus(c.getCooldownMinutes(), ChronoUnit.MINUTES))) {
                hidden.add(c.getQuizId());
            }
        }
        return hidden;
    }

    /** A deja un ticket (non rembourse) ou une partie sur ce quiz. */
    private boolean isParticipant(Quiz quiz, Long userId) {
        return userId != null
                && (ticketRepository.existsByQuizIdAndUserIdAndStatusNot(quiz.getId(), userId, TicketStatus.REFUNDED)
                        || gameSessionRepository.existsByQuizIdAndUserId(quiz.getId(), userId));
    }
}
