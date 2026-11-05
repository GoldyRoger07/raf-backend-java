package com.raikiservice.raf.leader.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.leader.service.LeaderService;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.AddPlayerRequest;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.CommissionRequest;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderCommission;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderPlayer;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.LeaderSummary;
import com.raikiservice.raf.leader.web.dto.LeaderDtos.PromoteRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leaders et leurs joueurs. Reserve au super-administrateur : la commission est de
 * l'argent verse, et nommer un leader revient a decider qui en touche.
 */
@RestController
@RequestMapping("/api/admin/leaders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration - Leaders")
public class AdminLeaderController {

    private final LeaderService leaderService;

    @GetMapping
    @Operation(summary = "Lister les leaders (recherche facultative), avec leur commission et leurs gains")
    public Page<LeaderSummary> list(@RequestParam(required = false) String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return leaderService.list(query, pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Nommer leader un compte joueur, avec sa commission en pourcentage")
    public LeaderSummary promote(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody PromoteRequest request) {
        return leaderService.promote(admin.getId(), request.userId(), request.commissionPercent());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Un leader")
    public LeaderSummary get(@PathVariable Long id) {
        return leaderService.get(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer le role leader (ses joueurs sont detaches, l'historique est garde)")
    public void demote(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id) {
        leaderService.demote(admin.getId(), id);
    }

    @PutMapping("/{id}/commission")
    @Operation(summary = "Changer la commission (pour les parties a venir)")
    public LeaderSummary setCommission(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id,
            @Valid @RequestBody CommissionRequest request) {
        return leaderService.setCommission(admin.getId(), id, request.commissionPercent());
    }

    @GetMapping("/{id}/players")
    @Operation(summary = "Joueurs rattaches au leader")
    public Page<LeaderPlayer> players(@PathVariable Long id, @PageableDefault(size = 20) Pageable pageable) {
        return leaderService.players(id, pageable);
    }

    @PostMapping("/{id}/players")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Rattacher un joueur au leader")
    public void addPlayer(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id,
            @Valid @RequestBody AddPlayerRequest request) {
        leaderService.addPlayer(admin.getId(), id, request.playerId());
    }

    @DeleteMapping("/{id}/players/{playerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un joueur au leader")
    public void removePlayer(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id,
            @PathVariable Long playerId) {
        leaderService.removePlayer(admin.getId(), id, playerId);
    }

    @GetMapping("/{id}/commissions")
    @Operation(summary = "Commissions versees au leader, detaillees par joueur")
    public Page<LeaderCommission> commissions(@PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        return leaderService.commissions(id, pageable);
    }
}
