package com.raikiservice.raf.admin.web;

import com.raikiservice.raf.admin.service.DailyStatsService;
import com.raikiservice.raf.admin.service.DashboardService;
import com.raikiservice.raf.admin.service.GameProfitService;
import com.raikiservice.raf.admin.service.PlayerFilter;
import java.time.LocalDate;

import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.admin.service.PlayerSearchService;
import com.raikiservice.raf.admin.web.dto.DailyStatsResponse;
import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.admin.web.dto.GameProfitResponse;
import com.raikiservice.raf.admin.web.dto.PlayerSummary;
import com.raikiservice.raf.game.service.SoloMasteryService;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Administration - Tableau de bord")
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final DailyStatsService dailyStatsService;
    private final GameProfitService gameProfitService;
    private final PlayerSearchService playerSearchService;
    private final SoloMasteryService soloMasteryService;

    @GetMapping("/dashboard")
    @Operation(summary = "Chiffres cles et travail en attente")
    public DashboardResponse dashboard() {
        return dashboardService.dashboard();
    }

    @GetMapping("/dashboard/daily")
    @Operation(summary = "Activite jour par jour (jours d'Haiti) : aujourd'hui, hier, hier a la meme heure, et la serie",
            description = "days : nombre de jours de la serie, aujourd'hui compris (2 a 90, 30 par defaut).")
    public DailyStatsResponse daily(@RequestParam(defaultValue = "30") int days) {
        return dailyStatsService.daily(days);
    }

    @GetMapping("/dashboard/profit")
    @Operation(summary = "Resultat du jeu sur une periode : ventes - remboursements - gains - commissions des leaders + frais de retrait")
    public GameProfitResponse profit(
            @RequestParam(defaultValue = "LAST_30_DAYS") GameProfitService.Period period) {
        return gameProfitService.profit(period);
    }

    @GetMapping("/players")
    @Operation(summary = "Rechercher et filtrer les joueurs, avec leurs soldes",
            description = "Tri : sort=name|email|creditBalance|winningsBalanceHtg|createdAt,asc|desc "
                    + "(par defaut les derniers inscrits). Les filtres absents ne filtrent pas.")
    public Page<PlayerSummary> players(@RequestParam(required = false) String query,
            @RequestParam(required = false) Boolean emailVerified,
            @RequestParam(required = false) Boolean phoneVerified,
            @RequestParam(required = false) Boolean hasCredit,
            @RequestParam(required = false) Boolean hasWinnings,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Boolean test,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Boolean hasToppedUp,
            @RequestParam(required = false) PlayerFilter.Activity activity,
            @PageableDefault(size = 20) Pageable pageable) {
        return playerSearchService.search(new PlayerFilter(query, emailVerified, phoneVerified, hasCredit,
                hasWinnings, status, test, createdFrom, createdTo, hasToppedUp, activity), pageable);
    }

    @GetMapping("/players/{id}")
    @Operation(summary = "Fiche d'un joueur, avec ses soldes")
    public PlayerSummary player(@PathVariable Long id) {
        return playerSearchService.get(id);
    }

    @GetMapping("/players/{id}/solo-mastery")
    @Operation(summary = "Questions deja reussies en solo par le joueur, sujet par sujet",
            description = "Ces questions ne lui sont plus servies sur le sujet tant que le vivier "
                    + "en compte assez d'autres.")
    public List<SoloMasteryService.SubjectCount> soloMastery(@PathVariable Long id) {
        playerSearchService.get(id);
        return soloMasteryService.countBySubject(id);
    }
}
