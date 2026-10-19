package com.raikiservice.raf.auth.web;

import java.util.Set;
import java.util.stream.Collectors;

import com.raikiservice.raf.auth.domain.Role;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.auth.service.RefreshTokenService;
import com.raikiservice.raf.auth.web.dto.UserResponse;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestion des comptes et des roles.
 *
 * <p>Reserve au SUPER_ADMIN : donner le role ADMIN ouvre l'acces a la validation
 * des retraits, donc a l'argent. Un ADMIN ne doit pas pouvoir s'auto-promouvoir ni
 * promouvoir un complice.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Administration - Comptes")
public class AdminUserController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    @GetMapping
    @Operation(summary = "Lister les comptes")
    @Transactional(readOnly = true)
    public Page<UserResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consulter un compte")
    @Transactional(readOnly = true)
    public UserResponse get(@PathVariable Long id) {
        return UserResponse.from(require(id));
    }

    @PostMapping("/{id}/roles")
    @Operation(summary = "Remplacer les roles d'un compte")
    @Transactional
    public UserResponse setRoles(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @RequestBody Set<RoleName> roleNames) {

        User user = require(id);
        String previous = user.getRoles().stream()
                .map(role -> role.getName().name())
                .sorted()
                .collect(Collectors.joining(","));

        Set<Role> roles = roleNames.stream()
                .map(name -> roleRepository.findByName(name)
                        .orElseThrow(() -> ApiException.notFound("Role " + name)))
                .collect(Collectors.toSet());

        user.setRoles(roles);

        // Les roles sont portes par le jeton d'acces : sans revocation, l'ancien
        // niveau de droits resterait valable jusqu'a son expiration.
        refreshTokenService.revokeAllAfterPasswordChange(id);

        auditService.record(admin.getId(), "USER_ROLES_CHANGED", "User", String.valueOf(id),
                previous, roleNames.toString());

        return UserResponse.from(user);
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "Suspendre, bannir ou reactiver un compte")
    @Transactional
    public UserResponse setStatus(@AuthenticationPrincipal UserPrincipal admin,
            @PathVariable Long id, @RequestBody UserStatus status) {

        User user = require(id);
        UserStatus previous = user.getStatus();
        user.setStatus(status);

        if (status != UserStatus.ACTIVE) {
            refreshTokenService.revokeAllAfterPasswordChange(id);
        }

        auditService.record(admin.getId(), "USER_STATUS_CHANGED", "User", String.valueOf(id),
                previous.name(), status.name());

        return UserResponse.from(user);
    }

    private User require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));
    }
}
