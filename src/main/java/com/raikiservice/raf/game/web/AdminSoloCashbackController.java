package com.raikiservice.raf.game.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.game.service.SoloCashbackService;
import com.raikiservice.raf.game.service.SoloCashbackService.Config;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cashback du quasi sans-faute en solo : reglages globaux. Chaque quiz peut remplacer le
 * taux (POST /api/admin/quizzes/{id}/cashback). Protege par la regle {@code /api/admin/**} ;
 * chaque reglage change est trace.
 */
@RestController
@RequestMapping("/api/admin/solo-cashback")
@RequiredArgsConstructor
@Tag(name = "Administration - Jeu")
public class AdminSoloCashbackController {

    private final SoloCashbackService soloCashbackService;

    @GetMapping
    @Operation(summary = "Cashback solo : interrupteur, taux par defaut, plafond par jour, minimum de questions")
    public Config get() {
        return soloCashbackService.config();
    }

    @PutMapping
    @Operation(summary = "Regler le cashback solo",
            description = "Partie solo payante terminee avec exactement une erreur : percent % du prix "
                    + "d'entree rendu en credits (arrondi inferieur, au moins 1), au plus dailyLimit fois "
                    + "par joueur et par jour, pour les parties d'au moins minQuestions questions.")
    public Config update(@AuthenticationPrincipal UserPrincipal admin, @Valid @RequestBody ConfigDto request) {
        return soloCashbackService.setConfig(admin.getId(), new Config(request.enabled(), request.percent(),
                request.dailyLimit(), request.minQuestions()));
    }

    public record ConfigDto(
            @NotNull Boolean enabled,
            @NotNull @Min(1) @Max(100) Integer percent,
            @NotNull @Min(1) @Max(SoloCashbackService.MAX_DAILY_LIMIT) Integer dailyLimit,
            @NotNull @Min(2) @Max(SoloCashbackService.MAX_MIN_QUESTIONS) Integer minQuestions) {
    }
}
