package com.raikiservice.raf.common.settings;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parametres applicatifs : valeur du credit, frais et plafonds de retrait,
 * reglages anti-triche du jeu.
 *
 * <p>Reserve au SUPER_ADMIN : ces valeurs pilotent des montants reels (changer
 * la valeur du credit reevalue d'un coup tous les soldes en circulation).
 * Chaque modification est tracee au journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Administration - Parametres")
public class AdminSettingsController {

    private final SettingsService settingsService;

    public record UpdateSettingDto(
            @NotBlank(message = "La valeur est obligatoire.")
            @Size(max = 20)
            String value) {
    }

    @GetMapping
    @Operation(summary = "Lister les parametres applicatifs")
    public List<SettingResponse> list() {
        return settingsService.list();
    }

    @PutMapping("/{key}")
    @Operation(summary = "Modifier un parametre existant (trace au journal d'audit)")
    public SettingResponse update(@AuthenticationPrincipal UserPrincipal admin, @PathVariable String key,
            @Valid @RequestBody UpdateSettingDto request) {
        return settingsService.updateByAdmin(admin.getId(), key, request.value());
    }
}
