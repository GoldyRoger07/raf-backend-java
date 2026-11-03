package com.raikiservice.raf.admin.web;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.service.AccountEmailService;
import com.raikiservice.raf.auth.web.dto.ChangeEmailRequest;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.common.web.TestFlagDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actions de la fiche joueur du back-office. Ouvertes aux administrateurs (regle
 * /api/admin/**), contrairement a la gestion des comptes et des roles, reservee aux
 * super-administrateurs.
 */
@RestController
@RequestMapping("/api/admin/players")
@RequiredArgsConstructor
@Tag(name = "Administration - Joueurs")
public class AdminPlayerController {

    private final UserRepository userRepository;
    private final AccountEmailService accountEmailService;
    private final AuditService auditService;

    @PostMapping("/{id}/test")
    @Operation(summary = "Marquer ou demarquer un compte de test (activite hors tableau de bord)")
    @Transactional
    public UserResponse setTest(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @RequestBody TestFlagDto request) {
        User user = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Joueur"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ApiException(ErrorCode.ACCOUNT_DELETED);
        }
        boolean previous = user.isTest();
        user.setTest(request.test());
        auditService.record(admin.getId(), "USER_TEST_FLAG_CHANGED", "User", String.valueOf(id),
                String.valueOf(previous), String.valueOf(request.test()));
        return UserResponse.from(user);
    }

    @PostMapping("/{id}/email")
    @Operation(summary = "Corriger l'email d'un joueur (repasse non verifie, nouveau lien envoye)")
    public UserResponse changeEmail(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @Valid @RequestBody ChangeEmailRequest request) {
        boolean superAdmin = admin.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_SUPER_ADMIN"));
        return accountEmailService.changeByAdmin(admin.getId(), superAdmin, id, request.email());
    }
}
