package com.raikiservice.raf.game.service;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.game.web.dto.AdminGameSessionResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vues back-office des parties. Assemblees dans la transaction : la vue touche
 * le joueur et le ticket, tous deux paresseux.
 */
@Service
@RequiredArgsConstructor
public class AdminGameService {

    private final GameSessionRepository gameSessionRepository;

    @Transactional(readOnly = true)
    public Page<AdminGameSessionResponse> sessions(Long quizId, boolean flaggedOnly,
            Pageable pageable) {
        return (flaggedOnly
                ? gameSessionRepository.findByQuizIdAndFlaggedTrueOrderByStartedAtDesc(quizId, pageable)
                : gameSessionRepository.findByQuizIdOrderByStartedAtDesc(quizId, pageable))
                .map(AdminGameSessionResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<AdminGameSessionResponse> actionableFlagged(Pageable pageable) {
        return gameSessionRepository.findActionableFlagged(pageable).map(AdminGameSessionResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminGameSessionResponse session(Long sessionId) {
        return gameSessionRepository.findById(sessionId)
                .map(AdminGameSessionResponse::from)
                .orElseThrow(() -> ApiException.notFound("Partie"));
    }
}
