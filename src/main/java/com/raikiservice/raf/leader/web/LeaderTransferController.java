package com.raikiservice.raf.leader.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.leader.service.LeaderTransferService;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferConfig;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferPlayer;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferRequest;
import com.raikiservice.raf.leader.web.dto.LeaderTransferDtos.TransferResponse;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Recharge de ses joueurs par un leader, depuis l'app joueur. */
@RestController
@RequestMapping("/api/leader/transfers")
@PreAuthorize("hasRole('LEADER')")
@RequiredArgsConstructor
@Tag(name = "Leader - Recharge des joueurs")
public class LeaderTransferController {

    private final LeaderTransferService transferService;

    @GetMapping("/config")
    @Operation(summary = "Bornes de montant, solde du leader et activation de la fonction")
    public TransferConfig config(@AuthenticationPrincipal UserPrincipal leader) {
        return transferService.config(leader.getId());
    }

    @GetMapping("/players")
    @Operation(summary = "Joueurs rattaches au leader, recherche par nom ou telephone")
    public Page<TransferPlayer> players(@AuthenticationPrincipal UserPrincipal leader,
            @RequestParam(required = false) String q, @PageableDefault(size = 20) Pageable pageable) {
        return transferService.players(leader.getId(), q, pageable);
    }

    @GetMapping
    @Operation(summary = "Recharges effectuees par le leader, les plus recentes d'abord")
    public Page<TransferResponse> history(@AuthenticationPrincipal UserPrincipal leader,
            @PageableDefault(size = 20) Pageable pageable) {
        return transferService.history(leader.getId(), pageable);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Recharger un joueur avec les credits du leader (irreversible)")
    public TransferResponse transfer(@AuthenticationPrincipal UserPrincipal leader,
            @Valid @RequestBody TransferRequest request) {
        return transferService.transfer(leader.getId(), request.playerId(), request.credits(),
                request.requestKey());
    }
}
