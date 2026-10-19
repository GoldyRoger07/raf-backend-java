package com.raikiservice.raf.auth.service;

import java.time.Instant;
import java.util.Set;

import com.raikiservice.raf.auth.domain.Role;
import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;
import com.raikiservice.raf.auth.repository.RoleRepository;
import com.raikiservice.raf.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cree le premier compte SUPER_ADMIN au demarrage.
 *
 * <p>Sans cela, personne ne peut acceder au back-office : l'inscription publique
 * ne donne que le role PLAYER, et aucun endpoint ne permet de s'elever soi-meme.
 *
 * <p>Ne fait rien si les variables ne sont pas renseignees, et ne touche jamais a
 * un compte existant : le mot de passe de configuration ne peut donc pas servir a
 * reprendre la main sur un administrateur deja cree.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin-email:}")
    private String adminEmail;

    @Value("${app.bootstrap.admin-phone:}")
    private String adminPhone;

    @Value("${app.bootstrap.admin-password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank() || adminPhone.isBlank()) {
            return;
        }

        String email = adminEmail.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return;
        }

        Role superAdmin = roleRepository.findByName(RoleName.SUPER_ADMIN)
                .orElseThrow(() -> new IllegalStateException("Role SUPER_ADMIN absent."));
        Role admin = roleRepository.findByName(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Role ADMIN absent."));

        User user = new User();
        user.setEmail(email);
        user.setPhone(adminPhone.trim());
        user.setPasswordHash(passwordEncoder.encode(adminPassword));
        user.setFirstName("Super");
        user.setLastName("Admin");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRoles(Set.of(superAdmin, admin));

        userRepository.save(user);
        log.warn("Compte SUPER_ADMIN initial cree pour {}. Changez son mot de passe "
                + "et retirez app.bootstrap.* de la configuration.", email);
    }
}
