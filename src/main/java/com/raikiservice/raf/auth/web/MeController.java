package com.raikiservice.raf.auth.web;

import java.util.List;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.service.RefreshTokenService;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.auth.web.dto.SessionResponse;
import com.raikiservice.raf.auth.web.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "Compte")
public class MeController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    @GetMapping
    @Operation(summary = "Profil de l'utilisateur connecte")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authService.currentUser(principal.getId());
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
