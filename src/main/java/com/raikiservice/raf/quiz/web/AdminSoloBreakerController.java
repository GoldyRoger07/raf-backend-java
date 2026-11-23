package com.raikiservice.raf.quiz.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.quiz.service.SoloBreakerService;
import com.raikiservice.raf.quiz.service.SoloBreakerService.Config;
import com.raikiservice.raf.quiz.service.SoloBreakerService.Overview;

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
 * Disjoncteur de rentabilite des quiz solo : reglages et etat des quiz surveilles.
 * Protege par la regle {@code /api/admin/**} ; chaque reglage change est trace.
 */
@RestController
@RequestMapping("/api/admin/solo-breaker")
@RequiredArgsConstructor
@Tag(name = "Administration - Fraude")
public class AdminSoloBreakerController {

    private final SoloBreakerService soloBreakerService;

    @GetMapping
    @Operation(summary = "Disjoncteur solo : reglages et fenetre de parties de chaque quiz solo ouvert et payant")
    public Overview overview() {
        return soloBreakerService.overview();
    }

    @PutMapping
    @Operation(summary = "Regler le disjoncteur solo",
            description = "Active : verification immediate, puis chaque minute. Un quiz qui perd est masque "
                    + "(jamais ferme) et les admins sont prevenus.")
    public Result update(@AuthenticationPrincipal UserPrincipal admin, @Valid @RequestBody ConfigDto request) {
        soloBreakerService.setConfig(admin.getId(), new Config(request.enabled(), request.windowGames(),
                request.minGames(), request.maxPayoutPercent()));
        int tripped = soloBreakerService.checkAll().trippedQuizIds().size();
        return new Result(tripped, soloBreakerService.overview());
    }

    public record ConfigDto(
            @NotNull Boolean enabled,
            @NotNull @Min(1) @Max(SoloBreakerService.MAX_WINDOW_GAMES) Integer windowGames,
            @NotNull @Min(1) @Max(SoloBreakerService.MAX_WINDOW_GAMES) Integer minGames,
            @NotNull @Min(1) @Max(SoloBreakerService.MAX_PAYOUT_PERCENT_LIMIT) Integer maxPayoutPercent) {
    }

    /** Reglages enregistres : quiz masques tout de suite, et l'ecran a jour. */
    public record Result(int tripped, Overview overview) {
    }
}
