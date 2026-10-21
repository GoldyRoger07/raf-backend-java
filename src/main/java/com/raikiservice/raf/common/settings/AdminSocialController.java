package com.raikiservice.raf.common.settings;

import com.raikiservice.raf.auth.security.UserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lien Instagram (administrateurs). Contrairement aux Parametres, il n'engage pas
 * d'argent : il n'est pas reserve au super-administrateur. Chaque modification
 * est tracee au journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/social")
@RequiredArgsConstructor
@Tag(name = "Administration - Reseaux sociaux")
public class AdminSocialController {

    private final SocialService socialService;

    public record UpdateSocialDto(
            boolean instagramEnabled,
            /** Adresse du profil, nom d'utilisateur ou @nom ; vide pour retirer le compte. */
            @Size(max = 200)
            String instagramAccount) {
    }

    @GetMapping
    @Operation(summary = "Lire le lien Instagram")
    public SocialService.Social get() {
        return socialService.get();
    }

    @PutMapping
    @Operation(summary = "Activer, desactiver ou changer le lien Instagram (trace au journal d'audit)")
    public SocialService.Social update(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody UpdateSocialDto request) {
        return socialService.update(admin.getId(), request.instagramEnabled(), request.instagramAccount());
    }
}
