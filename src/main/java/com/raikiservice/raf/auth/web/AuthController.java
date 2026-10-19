package com.raikiservice.raf.auth.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.service.AuthService;
import com.raikiservice.raf.auth.web.dto.ChangePasswordRequest;
import com.raikiservice.raf.auth.web.dto.ForgotPasswordRequest;
import com.raikiservice.raf.auth.web.dto.LoginRequest;
import com.raikiservice.raf.auth.web.dto.MessageResponse;
import com.raikiservice.raf.auth.web.dto.RefreshRequest;
import com.raikiservice.raf.auth.web.dto.RegisterRequest;
import com.raikiservice.raf.auth.web.dto.ResetPasswordRequest;
import com.raikiservice.raf.auth.web.dto.TokenOnlyRequest;
import com.raikiservice.raf.auth.web.dto.TokenResponse;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.auth.web.dto.VerifyOtpRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentification")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Creer un compte joueur et envoyer le lien de verification")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Se connecter et obtenir une paire de jetons")
    public TokenResponse login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Echanger un refresh token contre une nouvelle paire de jetons")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request,
            HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), httpRequest);
    }

    @PostMapping("/logout")
    @Operation(summary = "Fermer la session courante")
    public MessageResponse logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return new MessageResponse("Session fermee.");
    }

    @PostMapping("/email/verify")
    @Operation(summary = "Valider une adresse email a partir du jeton recu par email")
    public MessageResponse verifyEmail(@Valid @RequestBody TokenOnlyRequest request) {
        authService.verifyEmail(request.token());
        return new MessageResponse("Adresse email verifiee.");
    }

    @PostMapping("/email/resend")
    @Operation(summary = "Renvoyer le lien de verification")
    public MessageResponse resendVerification(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.resendEmailVerification(request.email());
        return new MessageResponse(
                "Si un compte non verifie correspond a cette adresse, un lien vient d'etre envoye.");
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "Demander un lien de reinitialisation de mot de passe")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        return new MessageResponse(
                "Si un compte correspond a cette adresse, un lien vient d'etre envoye.");
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Definir un nouveau mot de passe a partir du jeton recu par email")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return new MessageResponse("Mot de passe mis a jour. Reconnectez-vous.");
    }

    @PostMapping("/password/change")
    @Operation(summary = "Changer son mot de passe (ferme toutes les sessions)")
    public MessageResponse changePassword(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.getId(), request);
        return new MessageResponse("Mot de passe mis a jour. Reconnectez-vous.");
    }

    @PostMapping("/phone/send-otp")
    @Operation(summary = "Envoyer un code de verification par SMS")
    public MessageResponse sendPhoneOtp(@AuthenticationPrincipal UserPrincipal principal) {
        authService.sendPhoneOtp(principal.getId());
        return new MessageResponse("Code envoye par SMS.");
    }

    @PostMapping("/phone/verify")
    @Operation(summary = "Valider son numero de telephone avec le code recu")
    public MessageResponse verifyPhone(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyPhone(principal.getId(), request.code());
        return new MessageResponse("Numero de telephone verifie.");
    }
}
