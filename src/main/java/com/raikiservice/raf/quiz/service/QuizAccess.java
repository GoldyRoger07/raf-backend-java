package com.raikiservice.raf.quiz.service;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.quiz.domain.Quiz;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Qui voit les quiz de test : le staff (tout role autre que joueur) et les comptes
 * marques de test. Pour les autres, un quiz de test n'existe pas (404) : aucun vrai
 * joueur ne depense d'argent sur un quiz exclu de la comptabilite.
 */
@Component
@RequiredArgsConstructor
public class QuizAccess {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public boolean seesTestQuizzes(Long userId) {
        if (userId == null) {
            return false;
        }
        return userRepository.findById(userId)
                .map(user -> user.isTest()
                        || user.getRoles().stream().anyMatch(role -> role.getName() != RoleName.PLAYER))
                .orElse(false);
    }

    public void assertVisible(Quiz quiz, Long userId) {
        if (quiz.isTest() && !seesTestQuizzes(userId)) {
            throw ApiException.notFound("Quiz");
        }
    }
}
