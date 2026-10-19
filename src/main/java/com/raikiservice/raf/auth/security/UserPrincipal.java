package com.raikiservice.raf.auth.security;

import java.util.Collection;
import java.util.List;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.domain.UserStatus;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** Utilisateur authentifie tel que vu par Spring Security. */
@Getter
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String password;
    private final UserStatus status;
    private final boolean emailVerified;
    private final boolean phoneVerified;
    private final List<GrantedAuthority> authorities;

    public UserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.password = user.getPasswordHash();
        this.status = user.getStatus();
        this.emailVerified = user.isEmailVerified();
        this.phoneVerified = user.isPhoneVerified();
        this.authorities = user.getRoles().stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.getName().name()))
                .toList();
    }

    public UserPrincipal(Long id, String email, List<String> roles) {
        this.id = id;
        this.email = email;
        this.password = null;
        this.status = UserStatus.ACTIVE;
        this.emailVerified = false;
        this.phoneVerified = false;
        this.authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.BANNED && status != UserStatus.SUSPENDED;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }
}
