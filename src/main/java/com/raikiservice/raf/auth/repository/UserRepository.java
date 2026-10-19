package com.raikiservice.raf.auth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.auth.domain.RoleName;
import com.raikiservice.raf.auth.domain.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByPhone(String phone);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByPhone(String phone);

    /**
     * Recherche de comptes pour la gestion des roles : email ou nom (term), ou
     * telephone (digits) ; staffOnly restreint aux comptes ayant un role autre que
     * PLAYER.
     */
    @Query("select u from User u where "
            + "(lower(u.email) like :term or lower(concat(u.firstName, ' ', u.lastName)) like :term "
            + " or u.phone like :digits) "
            + "and (:staffOnly = false or exists (select r from u.roles r "
            + " where r.name <> com.raikiservice.raf.auth.domain.RoleName.PLAYER))")
    Page<User> searchAccounts(@Param("term") String term, @Param("digits") String digits,
            @Param("staffOnly") boolean staffOnly, Pageable pageable);

    /** Super-administrateurs actifs : il doit toujours en rester au moins un. */
    @Query("select count(distinct u) from User u join u.roles r "
            + "where r.name = com.raikiservice.raf.auth.domain.RoleName.SUPER_ADMIN "
            + "and u.status = com.raikiservice.raf.auth.domain.UserStatus.ACTIVE")
    long countActiveSuperAdmins();

    /** Comptes actifs portant l'un de ces roles (destinataires des alertes d'administration). */
    @Query("select distinct u.id from User u join u.roles r where r.name in :roles "
            + "and u.status = com.raikiservice.raf.auth.domain.UserStatus.ACTIVE")
    List<Long> findIdsByRoles(@Param("roles") Collection<RoleName> roles);
}
