package com.raikiservice.raf.game.service;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.game.domain.GameSessionStatus;
import com.raikiservice.raf.common.web.SortableFields;
import com.raikiservice.raf.common.web.SearchTerms;
import com.raikiservice.raf.game.repository.GameSessionRepository;
import com.raikiservice.raf.game.web.dto.AdminGameSessionResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        return sessions(quizId, flaggedOnly, null, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminGameSessionResponse> actionableFlagged(Pageable pageable) {
        return actionableFlagged(null, pageable);
    }

    /** Tris des listes de parties ; par defaut la plus recente d'abord. Score vide en fin de liste. */
    private static final SortableFields SORTS = SortableFields
            .of("id", Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")))
            .field("startedAt", "startedAt")
            .field("player", SortableFields.Key.of("u.firstName"), SortableFields.Key.of("u.lastName"))
            .field("quiz", "z.title")
            .field("score", SortableFields.Key.alwaysAscending("(case when s.score is null then 1 else 0 end)"),
                    SortableFields.Key.of("s.score"))
            .field("status", "status");

    /**
     * Parties d'un quiz, avec recherche (joueur, email, telephone, IP, numero de partie) et
     * statut facultatifs.
     */
    @Transactional(readOnly = true)
    public Page<AdminGameSessionResponse> sessions(Long quizId, boolean flaggedOnly, String query,
            GameSessionStatus status, Pageable pageable) {
        return list(quizId, flaggedOnly, false, query, status, pageable);
    }

    /** Signalements encore traitables, tous quiz confondus, avec recherche facultative. */
    @Transactional(readOnly = true)
    public Page<AdminGameSessionResponse> actionableFlagged(String query, Pageable pageable) {
        return list(null, true, true, query, null, pageable);
    }

    private Page<AdminGameSessionResponse> list(Long quizId, boolean flaggedOnly, boolean actionable,
            String query, GameSessionStatus status, Pageable pageable) {
        String text = query == null ? "" : query.trim();
        String digits = text.replaceAll("[^0-9]", "");
        Long id = text.matches("#?\\d{1,18}") ? Long.valueOf(digits) : null;
        String phone = digits.length() >= 4 ? "%" + digits + "%" : null;
        return gameSessionRepository.adminList(quizId, flaggedOnly, actionable, status,
                        SearchTerms.contains(text), phone, id, SORTS.apply(pageable))
                .map(AdminGameSessionResponse::from);
    }

    @Transactional(readOnly = true)
    public AdminGameSessionResponse session(Long sessionId) {
        return gameSessionRepository.findById(sessionId)
                .map(AdminGameSessionResponse::from)
                .orElseThrow(() -> ApiException.notFound("Partie"));
    }

    /**
     * Partie d'un mouvement du portefeuille : debit d'entree en solo ou gain du sans-faute.
     * Le debit d'entree ne porte pas le numero de la partie (cree avant elle) : la partie
     * garde celui du mouvement.
     */
    @Transactional(readOnly = true)
    public AdminGameSessionResponse sessionOfTransaction(Long transactionId) {
        return gameSessionRepository.findByEntryTransactionId(transactionId)
                .or(() -> gameSessionRepository.findByPrizeTransactionId(transactionId))
                .map(AdminGameSessionResponse::from)
                .orElseThrow(() -> ApiException.notFound("Partie"));
    }
}
