package com.raikiservice.raf.reconciliation.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.reconciliation.domain.ReconciliationTrigger;
import com.raikiservice.raf.reconciliation.service.ReconciliationService;
import com.raikiservice.raf.reconciliation.web.dto.ReconciliationRunResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Administration - Rapprochement comptable")
public class AdminReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/runs")
    @Operation(summary = "Lancer un rapprochement maintenant (bilan complet avec les ecarts)")
    public ResponseEntity<ReconciliationRunResponse> run(@AuthenticationPrincipal UserPrincipal admin) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reconciliationService.run(ReconciliationTrigger.MANUAL, admin.getId()));
    }

    @GetMapping("/runs")
    @Operation(summary = "Historique des rapprochements, du plus recent au plus ancien")
    public Page<ReconciliationRunResponse> history(@PageableDefault(size = 20) Pageable pageable) {
        return reconciliationService.history(pageable);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Detail d'un rapprochement, avec ses ecarts")
    public ReconciliationRunResponse detail(@PathVariable Long id) {
        return reconciliationService.detail(id);
    }
}
