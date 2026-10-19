package com.raikiservice.raf.admin.web;

import com.raikiservice.raf.admin.service.AuditQueryService;
import com.raikiservice.raf.admin.web.dto.AuditEntryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journal d'audit en lecture seule, pour les administrateurs : chacun voit les
 * actions de tous, la sienne comprise, sans pouvoir rien y changer.
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
@Tag(name = "Administration - Journal d'audit")
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    @GetMapping
    @Operation(summary = "Consulter le journal (filtres facultatifs), du plus recent au plus ancien")
    public Page<AuditEntryResponse> search(@RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Long actorId,
            @PageableDefault(size = 50) Pageable pageable) {
        return auditQueryService.search(action, entityType, entityId, actorId, pageable);
    }

    @GetMapping("/filters")
    @Operation(summary = "Actions et types d'objets presents dans le journal, pour les filtres")
    public AuditQueryService.Filters filters() {
        return auditQueryService.filters();
    }
}
