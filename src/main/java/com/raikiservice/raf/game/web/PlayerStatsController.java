package com.raikiservice.raf.game.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.game.service.PlayerStatsService;
import com.raikiservice.raf.game.web.dto.PlayerStatsResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Jeu")
public class PlayerStatsController {

    private final PlayerStatsService playerStatsService;

    @GetMapping("/api/me/stats")
    @Operation(summary = "Statistiques personnelles du joueur connecte")
    public PlayerStatsResponse stats(@AuthenticationPrincipal UserPrincipal principal) {
        return playerStatsService.statsOf(principal.getId());
    }
}
