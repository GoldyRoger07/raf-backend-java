package com.raikiservice.raf.notification.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.notification.domain.AnnouncementAudience;
import com.raikiservice.raf.notification.service.AnnouncementService;
import com.raikiservice.raf.notification.web.dto.AnnouncementRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementResponse;
import com.raikiservice.raf.notification.web.dto.AnnouncementTestRequest;
import com.raikiservice.raf.notification.web.dto.AnnouncementTestResponse;
import com.raikiservice.raf.notification.web.dto.AudienceResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Messages du back-office. Ouvert aux administrateurs (regle /api/admin/**) ;
 * l'envoi a tous les joueurs est reserve aux super-administrateurs, verifie par le
 * service.
 */
@RestController
@RequestMapping("/api/admin/announcements")
@RequiredArgsConstructor
@Tag(name = "Administration - Messages aux joueurs")
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    @Operation(summary = "Messages envoyes ou programmes, les plus recents d'abord")
    public Page<AnnouncementResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return announcementService.list(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Un message et le suivi de son envoi")
    public AnnouncementResponse get(@PathVariable Long id) {
        return announcementService.get(id);
    }

    @GetMapping("/audience")
    @Operation(summary = "Nombre de destinataires, avec email verifie et abonnes au push")
    public AudienceResponse audience(@RequestParam AnnouncementAudience audience,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(defaultValue = "false") boolean includeTest) {
        return announcementService.audience(audience, targetUserId, includeTest);
    }

    @PostMapping
    @Operation(summary = "Envoyer un message tout de suite ou le programmer")
    public ResponseEntity<AnnouncementResponse> create(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.create(admin.getId(), isSuperAdmin(admin), request));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Annuler un message programme pas encore parti")
    public AnnouncementResponse cancel(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id) {
        return announcementService.cancel(admin.getId(), isSuperAdmin(admin), id);
    }

    @PostMapping("/test")
    @Operation(summary = "M'envoyer le message pour verifier son rendu")
    public AnnouncementTestResponse test(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody AnnouncementTestRequest request) {
        return announcementService.sendTest(admin.getId(), request);
    }

    private static boolean isSuperAdmin(UserPrincipal admin) {
        return admin.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
    }
}
