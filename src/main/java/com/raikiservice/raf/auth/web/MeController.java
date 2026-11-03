package com.raikiservice.raf.auth.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.service.AccountDeletionService;
import com.raikiservice.raf.auth.service.AccountEmailService;
import com.raikiservice.raf.auth.service.AccountPhoneService;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.service.RefreshTokenService;
import com.raikiservice.raf.auth.web.dto.ChangeEmailRequest;
import com.raikiservice.raf.auth.web.dto.ChangePhoneRequest;
import com.raikiservice.raf.auth.web.dto.DeleteAccountRequest;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.auth.web.dto.SessionResponse;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.util.RequestContext;
import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "Compte")
public class MeController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final AccountDeletionService accountDeletionService;
    private final AccountEmailService accountEmailService;
    private final AccountPhoneService accountPhoneService;

    @GetMapping
    @Operation(summary = "Profil de l'utilisateur connecte")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal.getId());
    }

    /** Corrige une adresse mal saisie a l'inscription, tant qu'elle n'est pas verifiee. */
    @PostMapping("/email")
    @Operation(summary = "Corriger son email non verifie (mot de passe requis, nouveau lien envoye)")
    public UserResponse correctEmail(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangeEmailRequest request, HttpServletRequest http) {
        return accountEmailService.correctOwnEmail(principal.getId(), request.password(), request.email(),
                RequestContext.clientIp(http));
    }

    /** Corrige un numero mal saisi a l'inscription, tant qu'il n'est pas verifie. */
    @PostMapping("/phone")
    @Operation(summary = "Corriger son numero non verifie (mot de passe requis)")
    public UserResponse correctPhone(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePhoneRequest request, HttpServletRequest http) {
        return accountPhoneService.correctOwnPhone(principal.getId(), request.password(), request.phone(),
                RequestContext.clientIp(http));
    }

    /**
     * POST plutot que DELETE : le corps (mot de passe) d'un DELETE est ignore par
     * certains proxys et clients HTTP.
     */
    @PostMapping("/delete")
    @Operation(summary = "Supprimer son compte (anonymisation, sans retour)")
    public MessageResponse deleteAccount(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.deleteOwnAccount(principal.getId(), request);
        return new MessageResponse("Votre compte a ete supprime.");
    }

    @GetMapping("/sessions")
    @Operation(summary = "Lister les appareils connectes")
    public List<SessionResponse> sessions(@AuthenticationPrincipal UserPrincipal principal) {
        return refreshTokenService.listActiveSessions(principal.getId()).stream()
                .map(SessionResponse::from)
                .toList();
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "Deconnecter un appareil")
    public MessageResponse revokeSession(@AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long sessionId) {
        refreshTokenService.revokeSession(principal.getId(), sessionId);
        return new MessageResponse("Appareil deconnecte.");
    }

    @DeleteMapping("/sessions")
    @Operation(summary = "Deconnecter tous les appareils")
    public MessageResponse revokeAllSessions(@AuthenticationPrincipal UserPrincipal principal) {
        int revoked = refreshTokenService.revokeAll(principal.getId());
        return new MessageResponse(revoked + " session(s) fermee(s).");
    }
}
