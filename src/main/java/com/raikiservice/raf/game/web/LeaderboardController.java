package com.raikiservice.raf.game.web;

import com.raikiservice.raf.quiz.service.QuizService;
import com.raikiservice.raf.quiz.service.QuizAccess;
import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.service.SoloLeaderboardService;
import com.raikiservice.raf.game.web.dto.LeaderboardResponse;
import com.raikiservice.raf.game.web.dto.SoloLeaderboardResponse;
import com.raikiservice.raf.quiz.domain.Quiz;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Jeu")
public class LeaderboardController {

    private final RankingService rankingService;
    private final QuizAccess quizAccess;
    private final QuizService quizService;
    private final SoloLeaderboardService soloLeaderboardService;

    @GetMapping("/api/quizzes/{quizId}/leaderboard")
    @Operation(summary = "Classement final d'un quiz termine")
    public LeaderboardResponse leaderboard(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long quizId, @PageableDefault(size = 50) Pageable pageable) {
        quizAccess.assertVisible(quizService.require(quizId), player.getId());
        return rankingService.leaderboard(quizId, player.getId(), pageable);
    }

    @GetMapping("/api/quizzes/{quizId}/solo-leaderboard")
    @Operation(summary = "Classement d'un quiz solo : meilleurs scores et gains par joueur (noms masques)")
    public SoloLeaderboardResponse soloLeaderboard(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long quizId) {
        Quiz quiz = quizService.require(quizId);
        quizAccess.assertVisible(quiz, player.getId());
        return soloLeaderboardService.leaderboard(quiz, player.getId());
    }
}
