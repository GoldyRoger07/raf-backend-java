package com.raikiservice.raf.game.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.game.service.AdminGameService;
import com.raikiservice.raf.game.service.GameService;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.AdminGameSessionResponse;
import com.raikiservice.raf.game.web.dto.GameReviewResponse;
import com.raikiservice.raf.game.web.dto.InvalidateSessionDto;
import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.web.dto.QuizResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Supervision des parties : signalements, invalidation, cloture manuelle. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Administration - Jeu")
public class AdminGameController {

    private final AdminGameService adminGameService;
    private final GameService gameService;
    private final RankingService rankingService;
    private final QuizService quizService;

    @GetMapping("/quizzes/{quizId}/sessions")
    @Operation(summary = "Parties d'un quiz (flagged=true : seulement les signalees)")
    public Page<AdminGameSessionResponse> sessions(@PathVariable Long quizId,
            @RequestParam(defaultValue = "false") boolean flagged,
            @PageableDefault(size = 50) Pageable pageable) {
        return adminGameService.sessions(quizId, flagged, pageable);
    }

    @GetMapping("/game/sessions/flagged")
    @Operation(summary = "Parties signalees a revoir, tous quiz confondus (avant versement des gains)")
    public Page<AdminGameSessionResponse> flagged(@PageableDefault(size = 50) Pageable pageable) {
        return adminGameService.actionableFlagged(pageable);
    }

    @GetMapping("/game/sessions/{sessionId}/review")
    @Operation(summary = "Corrige d'une partie, a tout moment")
    public GameReviewResponse review(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long sessionId) {
        return gameService.review(sessionId);
    }

    @PostMapping("/game/sessions/{sessionId}/invalidate")
    @Operation(summary = "Invalider une partie (avant la cloture du quiz)")
    public AdminGameSessionResponse invalidate(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long sessionId, @Valid @RequestBody InvalidateSessionDto request) {
        gameService.invalidateByAdmin(admin.getId(), sessionId, request.reason());
        return adminGameService.session(sessionId);
    }

    @PostMapping("/quizzes/{quizId}/finalize")
    @Operation(summary = "Figer le classement et verser les gains sans attendre l'ordonnanceur")
    public QuizResponse finalizeQuiz(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long quizId) {
        rankingService.finalizeByAdmin(admin.getId(), quizId);
        return quizService.view(quizId);
    }
}
