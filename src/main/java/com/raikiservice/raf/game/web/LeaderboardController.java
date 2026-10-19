package com.raikiservice.raf.game.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.game.service.RankingService;
import com.raikiservice.raf.game.web.dto.LeaderboardResponse;

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

    @GetMapping("/api/quizzes/{quizId}/leaderboard")
    @Operation(summary = "Classement final d'un quiz termine")
    public LeaderboardResponse leaderboard(@AuthenticationPrincipal UserPrincipal player,
            @PathVariable Long quizId, @PageableDefault(size = 50) Pageable pageable) {
        return rankingService.leaderboard(quizId, player.getId(), pageable);
    }
}
