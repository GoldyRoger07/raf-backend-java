package com.raikiservice.raf.admin.web;

import com.raikiservice.raf.admin.service.DashboardService;
import com.raikiservice.raf.admin.service.PlayerSearchService;
import com.raikiservice.raf.admin.web.dto.DashboardResponse;
import com.raikiservice.raf.admin.web.dto.PlayerSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
    private final PlayerSearchService playerSearchService;

    @GetMapping("/dashboard")
    @Operation(summary = "Chiffres cles et travail en attente")
    public DashboardResponse dashboard() {
        return dashboardService.dashboard();
    }

    @GetMapping("/players")
    @Operation(summary = "Rechercher un joueur (email, nom, telephone), avec ses soldes")
    public Page<PlayerSummary> players(@RequestParam(required = false) String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return playerSearchService.search(query, pageable);
    }

    @GetMapping("/players/{id}")
    @Operation(summary = "Fiche d'un joueur, avec ses soldes")
    public PlayerSummary player(@PathVariable Long id) {
        return playerSearchService.get(id);
    }
}
