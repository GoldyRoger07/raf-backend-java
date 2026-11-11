package com.raikiservice.raf.fraud.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.fraud.service.FraudProfileService;
import com.raikiservice.raf.fraud.service.FraudProfileService.Profile;
import com.raikiservice.raf.fraud.service.FraudProfileService.ProfileRequest;
import com.raikiservice.raf.fraud.service.FraudProfileService.Suspect;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profils fraudeurs et joueurs suspectes. Protege par la regle {@code /api/admin/**} ;
 * chaque changement est trace au journal d'audit.
 */
@RestController
@RequestMapping("/api/admin/fraud")
@RequiredArgsConstructor
@Tag(name = "Administration - Fraude")
public class AdminFraudController {

    private final FraudProfileService fraudProfileService;

    @GetMapping("/profiles")
    @Operation(summary = "Profils fraudeurs : hausse du prix, baisse du temps, hausse du nombre de questions (solo)")
    public List<Profile> profiles() {
        return fraudProfileService.profiles();
    }

    @PostMapping("/profiles")
    @Operation(summary = "Creer un profil fraudeur")
    public List<Profile> createProfile(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody ProfileDto request) {
        fraudProfileService.createProfile(admin.getId(), request.toRequest());
        return fraudProfileService.profiles();
    }

    @PutMapping("/profiles/{id}")
    @Operation(summary = "Modifier un profil fraudeur (parties suivantes seulement)")
    public List<Profile> updateProfile(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id,
            @Valid @RequestBody ProfileDto request) {
        fraudProfileService.updateProfile(admin.getId(), id, request.toRequest());
        return fraudProfileService.profiles();
    }

    @DeleteMapping("/profiles/{id}")
    @Operation(summary = "Supprimer un profil fraudeur auquel aucun joueur n'est rattache")
    public List<Profile> deleteProfile(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long id) {
        fraudProfileService.deleteProfile(admin.getId(), id);
        return fraudProfileService.profiles();
    }

    @GetMapping("/suspects")
    @Operation(summary = "Joueurs suspectes de fraude et leur profil")
    public List<Suspect> suspects() {
        return fraudProfileService.suspects();
    }

    @PostMapping("/suspects")
    @Operation(summary = "Ajouter un joueur a la liste (deja liste : profil et note mis a jour)")
    public List<Suspect> addSuspect(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody SuspectDto request) {
        fraudProfileService.addSuspect(admin.getId(), request.userId(), request.profileId(), request.note());
        return fraudProfileService.suspects();
    }

    @DeleteMapping("/suspects/{userId}")
    @Operation(summary = "Retirer un joueur de la liste : ses prochaines parties reprennent les valeurs normales")
    public List<Suspect> removeSuspect(@AuthenticationPrincipal UserPrincipal admin, @PathVariable Long userId) {
        fraudProfileService.removeSuspect(admin.getId(), userId);
        return fraudProfileService.suspects();
    }

    public record ProfileDto(@NotBlank @Size(max = 80) String name, int priceIncreasePercent,
            int durationDecreasePercent, int questionsIncreasePercent) {

        ProfileRequest toRequest() {
            return new ProfileRequest(name, priceIncreasePercent, durationDecreasePercent, questionsIncreasePercent);
        }
    }

    public record SuspectDto(@NotNull Long userId, @NotNull Long profileId, @NotBlank @Size(max = 255) String note) {
    }
}
