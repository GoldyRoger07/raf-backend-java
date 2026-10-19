package com.raikiservice.raf.auth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    /** Comptes actifs portant l'un de ces roles (destinataires des alertes d'administration). */
    @Query("select distinct u.id from User u join u.roles r where r.name in :roles "
            + "and u.status = com.raikiservice.raf.auth.domain.UserStatus.ACTIVE")
    List<Long> findIdsByRoles(@Param("roles") Collection<RoleName> roles);
}
